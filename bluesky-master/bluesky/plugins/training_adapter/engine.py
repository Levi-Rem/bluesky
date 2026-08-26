"""Narrow boundary around the embedded BlueSky engine."""

from pathlib import Path
from math import isfinite, isclose
from typing import Any, Dict

from bluesky.tools.aero import ft, kts

from .airborne_performance import AirbornePerformanceCatalog


class BlueSkyEngine:
    """Owns one detached BlueSky instance for the default exercise group."""

    def __init__(self, workdir: str):
        import bluesky as bs

        self._bs = bs
        self._workdir = str(Path(workdir).resolve())
        self._initialized = False
        self._engine_state = "READY"
        self._direct_to_executions = {}
        self._route_change_receipts = {}
        self._reference_points = {}
        self._airborne_performance = AirbornePerformanceCatalog.load_default()

    def initialize(self) -> None:
        if self._initialized:
            return
        self._bs.init(mode="sim", detached=True, workdir=self._workdir)
        # Detached BlueSky promotes INIT to OP as soon as traffic exists.  Keep
        # the engine in HOLD until the platform explicitly sends START.
        self._bs.sim.hold()
        self._engine_state = "READY"
        self._direct_to_executions.clear()
        self._route_change_receipts.clear()
        self._reference_points.clear()
        self._initialized = True

    def health(self) -> Dict[str, Any]:
        if not self._initialized:
            return {
                "connected": False,
                "status": "DISCONNECTED",
                "performanceModel": "UNKNOWN",
                "message": "BlueSky 尚未初始化",
            }

        performance_model = str(
            getattr(self._bs.settings, "performance_model", "unknown")
        ).upper()
        return {
            "connected": True,
            "status": "CONNECTED",
            "performanceModel": performance_model,
            "message": "BlueSky 已连接",
            "airbornePerformance": {
                "sourceDatabase": self._airborne_performance.source_database,
                "scope": self._airborne_performance.scope,
                "loadType": self._airborne_performance.load_type,
                "aircraftTypeCount": len(self._airborne_performance.aircraft_types),
            },
        }

    def start(self) -> None:
        self._require_initialized()
        self._bs.sim.op()
        self._engine_state = "RUNNING"

    def pause(self) -> None:
        self._require_initialized()
        self._bs.sim.hold()
        self._engine_state = "PAUSED"

    def resume(self) -> None:
        self._require_initialized()
        self._bs.sim.op()
        self._engine_state = "RUNNING"

    def reset(self) -> Dict[str, Any]:
        self._require_initialized()
        self._bs.sim.reset()
        self._bs.sim.hold()
        self._engine_state = "READY"
        self._direct_to_executions.clear()
        self._route_change_receipts.clear()
        return {
            "engineState": self._engine_state,
            "simulationTimeSeconds": float(self._bs.sim.simt),
        }

    def update(self) -> None:
        self._require_initialized()
        self._bs.sim.update()
        self._observe_direct_to_progress()

    def create_aircraft(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self._require_initialized()
        callsign = str(payload["callsign"]).upper()
        aircraft_type = str(payload["aircraftType"]).upper()
        route = payload.get("routePoints") or payload.get("route", [])
        latitude, longitude = self._resolve_position(payload)
        self._validate_aircraft_type(aircraft_type)
        origin = self._point_code(payload.get("originPoint") or payload.get("origin", ""))
        destination = self._point_code(
            payload.get("destinationPoint") or payload.get("destination", "")
        )
        self._resolve_reference_point(payload.get("originPoint") or origin)
        self._resolve_reference_point(payload.get("destinationPoint") or destination)
        self._validate_airport(origin, "起飞机场")
        self._validate_airport(destination, "落地机场")
        self._validate_route(route)
        self._validate_initial_performance(
            aircraft_type,
            float(payload["altitudeFeet"]) * ft,
            float(payload["speedKnots"]) * kts,
        )
        if route and self._point_code(route[-1]) != destination:
            raise ValueError("航路最后一点必须是落地机场: {}".format(destination))

        result = self._bs.traf.cre(
            callsign,
            aircraft_type,
            latitude,
            longitude,
            float(payload["headingDegrees"]) % 360.0,
            float(payload["altitudeFeet"]) * ft,
            float(payload["speedKnots"]) * kts,
        )
        if isinstance(result, tuple) and result and result[0] is False:
            raise ValueError(result[1])

        try:
            index = self._bs.traf.id2idx(callsign)
            self._set_route(index, route)
            self._bs.traf.ap.orig[index] = origin
            self._bs.traf.ap.dest[index] = destination
            return self.aircraft_snapshot(callsign)
        except Exception:
            index = self._bs.traf.id2idx(callsign)
            if index >= 0:
                self._bs.traf.delete(index)
            raise

    def aircraft_snapshot(self, callsign: str) -> Dict[str, Any]:
        self._require_initialized()
        index = self._bs.traf.id2idx(callsign)
        if index < 0:
            raise ValueError("航空器不存在: {}".format(callsign))
        route = self._bs.traf.ap.route[index]
        active_index = max(route.iactwp, 0)
        snapshot = {
            "callsign": self._bs.traf.id[index],
            "aircraftType": self._bs.traf.type[index],
            "latitude": float(self._bs.traf.lat[index]),
            "longitude": float(self._bs.traf.lon[index]),
            "headingDegrees": float(self._bs.traf.hdg[index]),
            "altitudeFeet": float(self._bs.traf.alt[index] / ft),
            "speedKnots": float(self._bs.traf.cas[index] / kts),
            "mach": float(self._bs.traf.M[index]),
            "verticalSpeedFeetPerMinute": float(self._bs.traf.vs[index] / (ft / 60.0)),
            "route": list(route.wpname[active_index:]) if route.nwp else [],
        }
        direct_to = self._direct_to_executions.get(str(callsign).upper())
        if direct_to is not None:
            snapshot["directTo"] = {
                "commandId": direct_to["commandId"],
                "waypoint": direct_to["waypoint"],
                "passed": direct_to["passed"],
            }
        route_change = self._route_change_receipts.get(str(callsign).upper())
        if route_change is not None:
            snapshot["routeChange"] = {
                "commandId": route_change["commandId"],
                "activated": route_change["activated"],
            }
        performance_envelope = self._performance_envelope_snapshot(index)
        if performance_envelope is not None:
            snapshot["performanceEnvelope"] = performance_envelope
        return snapshot

    def _performance_envelope_snapshot(self, aircraft_index):
        aircraft_type = str(self._bs.traf.type[aircraft_index]).upper()
        if not self._airborne_performance.supports(aircraft_type):
            return None
        altitude = float(self._bs.traf.alt[aircraft_index])
        cruise = self._airborne_performance.envelope(aircraft_type, altitude, "CRUISE")
        climb = self._airborne_performance.envelope(aircraft_type, altitude, "CLIMB")
        descent = self._airborne_performance.envelope(aircraft_type, altitude, "DESCENT")
        return {
            "sourceDatabase": self._airborne_performance.source_database,
            "phase": "CRUISE",
            "minimumCasKnots": cruise.minimum_cas_mps / kts,
            "maximumCasKnots": cruise.maximum_cas_mps / kts,
            "maximumClimbFeetPerMinute": climb.maximum_vertical_rate_mps / (ft / 60.0),
            "maximumDescentFeetPerMinute": descent.maximum_vertical_rate_mps / (ft / 60.0),
        }

    def delete_aircraft(self, callsign: str) -> Dict[str, Any]:
        self._require_initialized()
        normalized = str(callsign).upper()
        index = self._bs.traf.id2idx(normalized)
        if index >= 0:
            self._bs.traf.delete(index)
        self._direct_to_executions.pop(normalized, None)
        self._route_change_receipts.pop(normalized, None)
        return {"callsign": normalized, "deleted": True}

    def snapshot(self) -> Dict[str, Any]:
        self._require_initialized()
        return {
            "simulationTimeSeconds": float(self._bs.sim.simt),
            "engineState": self._engine_state,
            "aircraft": [self.aircraft_snapshot(callsign) for callsign in self._bs.traf.id],
        }

    def execute_instruction(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self._require_initialized()
        callsign = str(payload["callsign"]).upper()
        index = self._bs.traf.id2idx(callsign)
        if index < 0:
            raise ValueError("航空器不存在: {}".format(callsign))
        instruction_type = str(payload["type"]).upper()
        result = {"callsign": callsign, "type": instruction_type, "accepted": True}

        if instruction_type == "HDG":
            self._bs.traf.ap.selhdgcmd(index, float(payload["headingDegrees"]) % 360.0)
            self._direct_to_executions.pop(callsign, None)
        elif instruction_type == "ALT":
            altitude = float(payload["altitudeFeet"]) * ft
            self._validate_altitude_ceiling(index, altitude)
            vertical_speed = payload.get("verticalSpeedFeetPerMinute")
            converted_vs = None
            if vertical_speed is not None:
                requested_vs = float(vertical_speed) * ft / 60.0
                converted_vs, performance_result = self._limit_vertical_speed(
                    index, altitude, requested_vs
                )
                result.update(performance_result)
            self._bs.traf.ap.selaltcmd(index, altitude, converted_vs)
        elif instruction_type == "SPD":
            speed = float(payload["speedKnots"]) * kts
            self._validate_speed_envelope(index, speed)
            self._bs.traf.ap.selspdcmd(index, speed)
        elif instruction_type == "MACH":
            self._bs.traf.ap.selspdcmd(index, float(payload["mach"]))
        elif instruction_type == "DCT":
            waypoint = self._point_code(
                payload.get("waypointPoint") or payload.get("waypoint", "")
            )
            self._resolve_reference_point(payload.get("waypointPoint") or waypoint)
            command_id = str(payload.get("commandId", "")).strip()
            if not command_id:
                raise ValueError("DCT 缺少 commandId")
            route = self._bs.traf.ap.route[index]
            if waypoint not in route.wpname:
                raise ValueError("直飞点不在当前航路中: {}".format(waypoint))
            route.direct(index, waypoint)
            self._bs.traf.swlnav[index] = True
            self._direct_to_executions[callsign] = {
                "commandId": command_id,
                "waypoint": waypoint,
                "targetIndex": route.iactwp,
                "passed": False,
            }
        elif instruction_type == "RTE":
            command_id = str(payload.get("commandId", "")).strip()
            if not command_id:
                raise ValueError("RTE 缺少 commandId")
            replacement = payload.get("routePoints") or payload.get("route", [])
            destination = str(self._bs.traf.ap.dest[index]).upper()
            if not replacement or self._point_code(replacement[-1]) != destination:
                raise ValueError("RTE 最后一点必须是落地机场: {}".format(destination))
            self._validate_route(replacement)
            self._replace_route(index, replacement)
            self._direct_to_executions.pop(callsign, None)
            self._route_change_receipts[callsign] = {
                "commandId": command_id,
                "activated": True,
            }
        else:
            raise ValueError("不支持的指令类型: {}".format(instruction_type))

        return result

    def _validate_altitude_ceiling(self, aircraft_index, target_altitude):
        aircraft_type = str(self._bs.traf.type[aircraft_index]).upper()
        if not self._airborne_performance.supports(aircraft_type):
            return
        ceiling = self._airborne_performance.ceiling_meters(aircraft_type)
        if target_altitude > ceiling:
            raise ValueError(
                "ALT 超过性能库升限: 允许不高于 {:.0f} ft".format(ceiling / ft)
            )

    def _validate_speed_envelope(self, aircraft_index, requested_cas):
        aircraft_type = str(self._bs.traf.type[aircraft_index]).upper()
        self._validate_initial_performance(
            aircraft_type,
            float(self._bs.traf.alt[aircraft_index]),
            requested_cas,
        )

    def _validate_initial_performance(self, aircraft_type, altitude, requested_cas):
        if not self._airborne_performance.supports(aircraft_type):
            return
        envelope = self._airborne_performance.envelope(
            aircraft_type,
            altitude,
            "CRUISE",
        )
        if not envelope.minimum_cas_mps <= requested_cas <= envelope.maximum_cas_mps:
            raise ValueError(
                "SPD 超出当前高度速度包线: 允许 {:.0f}-{:.0f} kt".format(
                    envelope.minimum_cas_mps / kts,
                    envelope.maximum_cas_mps / kts,
                )
            )

    def _limit_vertical_speed(self, aircraft_index, target_altitude, requested_vs):
        aircraft_type = str(self._bs.traf.type[aircraft_index]).upper()
        if not self._airborne_performance.supports(aircraft_type):
            return requested_vs, {
                "performanceLimitApplied": False,
                "requestedVerticalSpeedFeetPerMinute": requested_vs / (ft / 60.0),
                "appliedVerticalSpeedFeetPerMinute": requested_vs / (ft / 60.0),
            }
        current_altitude = float(self._bs.traf.alt[aircraft_index])
        phase = "CLIMB" if target_altitude > current_altitude else "DESCENT"
        envelope = self._airborne_performance.envelope(
            aircraft_type, current_altitude, phase
        )
        applied_magnitude = min(
            abs(requested_vs), envelope.maximum_vertical_rate_mps
        )
        applied_vs = applied_magnitude if phase == "CLIMB" else -applied_magnitude
        return applied_vs, {
            "performanceLimitApplied": abs(applied_vs - requested_vs) > 1e-9,
            "requestedVerticalSpeedFeetPerMinute": requested_vs / (ft / 60.0),
            "appliedVerticalSpeedFeetPerMinute": applied_vs / (ft / 60.0),
            "performanceSource": self._airborne_performance.source_database,
            "performancePhase": phase,
        }

    def _observe_direct_to_progress(self) -> None:
        for callsign, execution in list(self._direct_to_executions.items()):
            if execution["passed"]:
                continue
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                self._direct_to_executions.pop(callsign, None)
                continue
            route = self._bs.traf.ap.route[index]
            target_index = execution["targetIndex"]
            if route.iactwp > target_index or (
                target_index == route.nwp - 1 and not self._bs.traf.swlnav[index]
            ):
                execution["passed"] = True

    def search_reference(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self._require_initialized()
        kind = str(payload.get("kind", "")).upper()
        query = str(payload.get("query", "")).strip().upper()
        limit = max(1, min(int(payload.get("limit", 20)), 50))

        if kind == "AIRPORT":
            items = self._search_runtime_points(query, limit, True)
            return {"kind": kind, "items": items}

        if kind == "WAYPOINT":
            items = self._search_runtime_points(query, limit, False)
            return {"kind": kind, "items": items}

        if kind == "AIRCRAFT_TYPE":
            supported = self._supported_aircraft_types()
            matches = sorted(code for code in supported if not query or query in code)
            return {
                "kind": kind,
                "items": [
                    {
                        "code": code,
                        "name": code,
                        "airbornePerformanceAvailable": (
                            self._airborne_performance.supports(code)
                        ),
                        "performanceSourceAircraftType": (
                            self._airborne_performance.source_aircraft_type(code)
                            if self._airborne_performance.supports(code)
                            else None
                        ),
                    }
                    for code in matches[:limit]
                ],
            }

        raise ValueError("不支持的参考数据类型: {}".format(kind))

    def sync_reference_data(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Validate a complete point catalog before replacing the active catalog."""
        raw_points = payload.get("points")
        if not isinstance(raw_points, list) or not raw_points:
            raise ValueError("导航参考点全集不能为空")
        supported_types = {"WAYPOINT", "AIRPORT", "VOR", "NDB", "DME", "VOR_DME", "ILS"}
        candidate = {}
        counts = {}
        point_ids = set()
        for raw in raw_points:
            if not isinstance(raw, dict):
                raise ValueError("导航参考点必须是对象")
            point_id = str(raw.get("id", "")).strip()
            code = str(raw.get("code", "")).strip().upper()
            point_type = str(raw.get("type", "")).strip().upper()
            if not point_id or not code:
                raise ValueError("导航参考点 id/code 不能为空")
            if point_id in point_ids:
                raise ValueError("导航参考点 ID 重复: {}".format(point_id))
            point_ids.add(point_id)
            if code in candidate:
                raise ValueError("导航参考点代码重复: {}".format(code))
            if point_type not in supported_types:
                raise ValueError("导航参考点类型不支持: {}".format(point_type))
            try:
                if isinstance(raw["latitude"], bool) or isinstance(raw["longitude"], bool):
                    raise ValueError
                latitude = float(raw["latitude"])
                longitude = float(raw["longitude"])
            except (KeyError, TypeError, ValueError):
                raise ValueError("导航参考点坐标非法: {}".format(code))
            if (not isfinite(latitude) or not isfinite(longitude)
                    or not -90.0 <= latitude <= 90.0
                    or not -180.0 <= longitude <= 180.0):
                raise ValueError("导航参考点坐标越界: {}".format(code))
            elevation = raw.get("elevationMeters")
            if elevation is not None:
                if isinstance(elevation, bool) or not isinstance(elevation, (int, float)) \
                        or not isfinite(float(elevation)):
                    raise ValueError("导航参考点高程非法: {}".format(code))
            candidate[code] = {
                "id": point_id,
                "code": code,
                "type": point_type,
                "latitude": latitude,
                "longitude": longitude,
                "elevationMeters": elevation,
            }
            counts[point_type] = counts.get(point_type, 0) + 1
        self._reference_points = candidate
        return {"accepted": True, "totalCount": len(candidate), "counts": counts}

    def _search_runtime_points(self, query, limit, airports):
        matches = []
        for point in self._reference_points.values():
            if airports != (point["type"] == "AIRPORT"):
                continue
            if query and query not in point["code"]:
                continue
            matches.append({
                "code": point["code"],
                "name": point["code"],
                "latitude": point["latitude"],
                "longitude": point["longitude"],
            })
        return sorted(matches, key=lambda item: item["code"])[:limit]

    def _replace_route(self, aircraft_index: int, waypoint_names) -> None:
        from bluesky.traffic.route import Route

        route = self._bs.traf.ap.route[aircraft_index]
        old_data = {
            "wpname": list(route.wpname),
            "wptype": list(route.wptype),
            "wplat": list(route.wplat),
            "wplon": list(route.wplon),
            "wpalt": list(route.wpalt),
            "wpspd": list(route.wpspd),
            "wprta": list(route.wprta),
            "wpflyby": list(route.wpflyby),
            "wpflyturn": list(route.wpflyturn),
            "wpturnbank": list(route.wpturnbank),
            "wpturnrad": list(route.wpturnrad),
            "wpturnspd": list(route.wpturnspd),
            "wpturnhdgr": list(route.wpturnhdgr),
            "wpstack": list(route.wpstack),
            "nwp": route.nwp,
            "iactwp": route.iactwp,
        }
        try:
            for key in old_data:
                if key in ("nwp", "iactwp"):
                    continue
                setattr(route, key, [])
            route.nwp = 0
            route.iactwp = -1
            for item in waypoint_names:
                name = self._point_code(item)
                point = self._resolve_reference_point(item)
                result = route.addwpt(
                    aircraft_index,
                    name,
                    Route.wplatlon,
                    point["latitude"],
                    point["longitude"],
                )
                if result < 0:
                    raise ValueError("航路点无法加入航路: {}".format(name))
            if route.nwp:
                route.direct(aircraft_index, route.wpname[0])
                self._bs.traf.swlnav[aircraft_index] = True
        except Exception:
            for key, value in old_data.items():
                setattr(route, key, value)
            if route.nwp and 0 <= route.iactwp < route.nwp:
                route.direct(aircraft_index, route.wpname[route.iactwp])
            raise

    def _resolve_position(self, payload: Dict[str, Any]):
        latitude = payload.get("latitude")
        longitude = payload.get("longitude")
        if latitude is not None and longitude is not None:
            return float(latitude), float(longitude)
        initial = payload.get("initialPoint") or payload.get("initialWaypoint", "")
        initial_waypoint = self._point_code(initial)
        if not initial_waypoint:
            raise ValueError("必须提供经纬度或初始航路点")
        point = self._resolve_reference_point(initial)
        return point["latitude"], point["longitude"]

    def _validate_route(self, route):
        for point in route:
            self._resolve_reference_point(point)

    @staticmethod
    def _point_code(point):
        value = point.get("code") if isinstance(point, dict) else point
        return str(value).strip().upper()

    def _resolve_reference_point(self, value):
        code = self._point_code(value)
        point = self._reference_points.get(code)
        if point is None:
            raise ValueError("未知航路点或机场: {}".format(code))
        if isinstance(value, dict):
            try:
                latitude = float(value["latitude"])
                longitude = float(value["longitude"])
            except (KeyError, TypeError, ValueError):
                raise ValueError("导航参考点对象与已同步数据不一致: {}".format(code))
            if (str(value.get("id", "")).strip() != point["id"]
                    or str(value.get("type", "")).strip().upper() != point["type"]
                    or not isclose(latitude, point["latitude"], rel_tol=0.0, abs_tol=1e-9)
                    or not isclose(longitude, point["longitude"], rel_tol=0.0, abs_tol=1e-9)):
                raise ValueError("导航参考点对象与已同步数据不一致: {}".format(code))
        return point

    def _validate_aircraft_type(self, aircraft_type):
        supported = self._supported_aircraft_types()
        if not supported:
            raise ValueError("当前 BlueSky 性能模型不提供可用的固定翼机型目录")
        if aircraft_type not in supported:
            raise ValueError("未知机型: {}".format(aircraft_type))

    def _supported_aircraft_types(self):
        coefficient = getattr(self._bs.traf.perf, "coeff", None)
        if coefficient is None:
            return set()
        # 首版训练平台只创建固定翼航空器。OpenAP 的 rotor 集合还包含
        # Bob/Echo/Super 等内部别名，不能作为平台机型代码暴露。
        return {
            str(name).upper()
            for name in getattr(coefficient, "actypes_fixwing", ())
        }

    def _validate_airport(self, airport, field_name):
        point = self._reference_points.get(str(airport).strip().upper())
        if not point or point["type"] != "AIRPORT":
            raise ValueError("未知{}: {}".format(field_name, airport))

    def _set_route(self, aircraft_index: int, waypoint_names) -> None:
        from bluesky.traffic.route import Route

        route = self._bs.traf.ap.route[aircraft_index]
        for item in waypoint_names:
            name = str(item.get("code") if isinstance(item, dict) else item).upper()
            point = self._resolve_reference_point(item)
            result = route.addwpt(
                aircraft_index,
                name,
                Route.wplatlon,
                point["latitude"],
                point["longitude"],
            )
            if result < 0:
                raise ValueError("航路点无法加入航路: {}".format(name))
        if route.nwp:
            route.direct(aircraft_index, route.wpname[0])
            self._bs.traf.swlnav[aircraft_index] = True

    def _require_initialized(self) -> None:
        if not self._initialized:
            raise RuntimeError("BlueSky 尚未初始化")
