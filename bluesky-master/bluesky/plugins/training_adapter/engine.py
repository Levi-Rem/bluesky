"""Narrow boundary around the embedded BlueSky engine."""

from pathlib import Path
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
        route = [str(name).upper() for name in payload.get("route", [])]
        latitude, longitude = self._resolve_position(payload)
        self._validate_aircraft_type(aircraft_type)
        origin = str(payload.get("origin", "")).upper()
        destination = str(payload.get("destination", "")).upper()
        self._validate_airport(origin, "起飞机场")
        self._validate_airport(destination, "落地机场")
        self._validate_route(route)
        self._validate_initial_performance(
            aircraft_type,
            float(payload["altitudeFeet"]) * ft,
            float(payload["speedKnots"]) * kts,
        )
        if route and route[-1] != destination:
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
            waypoint = str(payload["waypoint"]).upper()
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
            replacement = [str(name).upper() for name in payload.get("route", [])]
            destination = str(self._bs.traf.ap.dest[index]).upper()
            if not replacement or replacement[-1] != destination:
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
            items = []
            for index, code in enumerate(self._bs.navdb.aptid):
                name = str(self._bs.navdb.aptname[index])
                if query and query not in str(code).upper() and query not in name.upper():
                    continue
                items.append(
                    {
                        "code": str(code).upper(),
                        "name": name,
                        "latitude": float(self._bs.navdb.aptlat[index]),
                        "longitude": float(self._bs.navdb.aptlon[index]),
                    }
                )
                if len(items) >= limit:
                    break
            return {"kind": kind, "items": items}

        if kind == "WAYPOINT":
            items = []
            seen = set()
            for index, code in enumerate(self._bs.navdb.wpid):
                normalized = str(code).upper()
                if normalized in seen or (query and query not in normalized):
                    continue
                seen.add(normalized)
                items.append(
                    {
                        "code": normalized,
                        "name": str(self._bs.navdb.wpdesc[index]),
                        "latitude": float(self._bs.navdb.wplat[index]),
                        "longitude": float(self._bs.navdb.wplon[index]),
                    }
                )
                if len(items) >= limit:
                    break
            return {"kind": kind, "items": items}

        if kind == "AIRCRAFT_TYPE":
            coefficient = self._bs.traf.perf.coeff
            supported = {str(name).upper() for name in coefficient.actypes_fixwing}
            supported.update(str(name).upper() for name in coefficient.actypes_rotor)
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
            for name in waypoint_names:
                result = route.addwpt(
                    aircraft_index,
                    name,
                    Route.wpnav,
                    float(self._bs.traf.lat[aircraft_index]),
                    float(self._bs.traf.lon[aircraft_index]),
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
        initial_waypoint = str(payload.get("initialWaypoint", "")).upper()
        if not initial_waypoint:
            raise ValueError("必须提供经纬度或初始航路点")
        index = self._bs.navdb.getwpidx(initial_waypoint)
        if index >= 0:
            return float(self._bs.navdb.wplat[index]), float(self._bs.navdb.wplon[index])
        airport_index = self._bs.navdb.getaptidx(initial_waypoint)
        if airport_index >= 0:
            return (
                float(self._bs.navdb.aptlat[airport_index]),
                float(self._bs.navdb.aptlon[airport_index]),
            )
        raise ValueError("未知初始航路点: {}".format(initial_waypoint))

    def _validate_route(self, route):
        for name in route:
            if self._bs.navdb.getwpidx(name) < 0 and self._bs.navdb.getaptidx(name) < 0:
                raise ValueError("未知航路点或机场: {}".format(name))

    def _validate_aircraft_type(self, aircraft_type):
        coefficient = self._bs.traf.perf.coeff
        supported = {str(name).upper() for name in coefficient.actypes_fixwing}
        supported.update(str(name).upper() for name in coefficient.actypes_rotor)
        if aircraft_type not in supported:
            raise ValueError("未知机型: {}".format(aircraft_type))

    def _validate_airport(self, airport, field_name):
        if not airport or self._bs.navdb.getaptidx(airport) < 0:
            raise ValueError("未知{}: {}".format(field_name, airport))

    def _set_route(self, aircraft_index: int, waypoint_names) -> None:
        from bluesky.traffic.route import Route

        route = self._bs.traf.ap.route[aircraft_index]
        for name in waypoint_names:
            result = route.addwpt(
                aircraft_index,
                name,
                Route.wpnav,
                float(self._bs.traf.lat[aircraft_index]),
                float(self._bs.traf.lon[aircraft_index]),
            )
            if result < 0:
                raise ValueError("航路点无法加入航路: {}".format(name))
        if route.nwp:
            route.direct(aircraft_index, route.wpname[0])
            self._bs.traf.swlnav[aircraft_index] = True

    def _require_initialized(self) -> None:
        if not self._initialized:
            raise RuntimeError("BlueSky 尚未初始化")
