"""Narrow boundary around the embedded BlueSky engine."""

from pathlib import Path
from math import isfinite, isclose, cos, radians
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
        # 迁移桥复杂引导控制器（v2 参数字典驱动；Protocol 2.0 接线后由
        # engine_v2 同样复用这些执行语义）
        self._takeoff_receipts = {}
        self._heading_receipts = {}
        self._hold_executions = {}
        self._orbit_executions = {}
        self._vor_executions = {}
        self._ils_approaches = {}
        self._missed_receipts = {}
        self._simple_receipts = {}
        self._flight_events = {}
        self._active_instructions = {}
        self._offset_routes = {}
        self._managed_speeds = {}
        # 验收/演示可选仿真倍率：预算与稳定窗均按仿真秒计，倍率只缩短墙钟时间
        import os
        self._sim_rate = max(1.0, float(os.environ.get("BLUESKY_SIM_RATE", "1") or 1))

    def initialize(self) -> None:
        if self._initialized:
            return
        self._bs.init(mode="sim", detached=True, workdir=self._workdir)
        self._apply_sim_rate()
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

    def simulation_time(self) -> float:
        """轻量仿真时钟读取（评审 A11：事件/响应时间戳来源）。"""
        if not self._initialized:
            return 0.0
        return float(self._bs.sim.simt)

    def is_initialized(self) -> bool:
        return self._initialized

    def start(self) -> None:
        self._require_initialized()
        self._bs.sim.op()
        self._apply_sim_rate()
        self._engine_state = "RUNNING"

    def pause(self) -> None:
        self._require_initialized()
        self._bs.sim.hold()
        self._engine_state = "PAUSED"

    def resume(self) -> None:
        self._require_initialized()
        self._bs.sim.op()
        self._apply_sim_rate()
        self._engine_state = "RUNNING"

    def _apply_sim_rate(self) -> None:
        # op()/hold() 会把 dtmult 重置为 1.0：运行态切换后必须重新应用倍率
        if self._sim_rate > 1.0:
            self._bs.sim.set_dtmult(self._sim_rate)

    def stop(self) -> None:
        self._require_initialized()
        self._bs.sim.hold()
        self._engine_state = "STOPPED"

    def reset(self) -> Dict[str, Any]:
        self._require_initialized()
        self._bs.sim.reset()
        self._bs.sim.hold()
        self._engine_state = "READY"
        self._direct_to_executions.clear()
        self._route_change_receipts.clear()
        self._clear_controllers()
        return {
            "engineState": self._engine_state,
            "simulationTimeSeconds": float(self._bs.sim.simt),
        }

    def update(self) -> None:
        self._require_initialized()
        self._bs.sim.update()
        self._observe_direct_to_progress()
        self._update_takeoff_receipts()
        self._update_holds()
        self._update_orbits()
        self._update_vor_intercepts()
        self._update_ils_approaches()
        self._update_missed_receipts()

    # ---- 迁移桥复杂引导控制器（每仿真 tick 驱动）----

    def _update_takeoff_receipts(self) -> None:
        for callsign, receipt in self._takeoff_receipts.items():
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                continue
            if (not receipt["airborne"] and self._bs.traf.vs[index] > 100.0 * ft / 60.0
                    and self._bs.traf.alt[index] > (receipt.get("elevationFt",0.0)+35.0)*ft):
                receipt["airborne"] = True
            if receipt["airborne"] and not receipt["takeoffEventEmitted"]:
                receipt["takeoffEventEmitted"] = True
                self._emit_flight_event(callsign, "TAKEOFF",
                                        "takeoff:" + receipt["commandId"],
                                        "起飞爬升建立")

    def _update_holds(self) -> None:
        for callsign, hold in self._hold_executions.items():
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                continue
            sim_time = float(self._bs.sim.simt)
            lat = float(self._bs.traf.lat[index])
            lon = float(self._bs.traf.lon[index])
            bearing, distance_nm = self._qdrdist_nm(lat, lon, hold["fixLat"], hold["fixLon"])
            turn = 1.0 if hold["turnDirection"] == "R" else -1.0
            if hold["phase"] == "DIRECTING":
                # 捕获门限必须大于纯方位追踪的极限环半径（≈2.5×转弯半径），
                # 否则航空器绕定位点盘旋永远进不了 OUTBOUND
                speed_kts = float(self._bs.traf.cas[index]) / kts
                turn_radius_nm = (speed_kts / 3600.0) / 0.0436  # 2.5°/s 名义转弯率
                capture_nm = max(1.5, 2.5 * turn_radius_nm)
                if distance_nm > capture_nm and not hold.get("lnavDirect"):
                    # 修复点不在当前航路：按方位转向引导
                    self._bs.traf.swlnav[index] = False
                    self._bs.traf.ap.selhdgcmd(index, bearing)
                if distance_nm <= capture_nm:
                    hold["phase"] = "OUTBOUND"
                    hold["phaseSince"] = sim_time
                    if hold.get("lnavDirect"):
                        self._bs.traf.swlnav[index] = False
                    self._bs.traf.ap.selhdgcmd(
                        index, (hold["inboundHdg"] + 180.0) % 360.0)
            elif hold["phase"] == "OUTBOUND":
                if sim_time - hold["phaseSince"] >= hold["legSeconds"]:
                    hold["phase"] = "INBOUND"
                    hold["phaseSince"] = sim_time
                    self._bs.traf.ap.selhdgcmd(index, hold["inboundHdg"])
                    hold["established"] = True
            elif hold["phase"] == "INBOUND":
                if sim_time - hold["phaseSince"] >= hold["legSeconds"]:
                    hold["phase"] = "OUTBOUND"
                    hold["phaseSince"] = sim_time
                    self._bs.traf.ap.selhdgcmd(
                        index, (hold["inboundHdg"] + 180.0) % 360.0)

    def _update_orbits(self) -> None:
        for callsign, orbit in self._orbit_executions.items():
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                continue
            # 持续标准转弯维持盘旋：按速度/半径计算转弯率
            speed_kts = float(self._bs.traf.cas[index]) / kts
            turn_rate = max(0.5, min(3.0, speed_kts / (60.0 * max(orbit["radiusNm"], 0.5))))
            direction = 1.0 if orbit["turnDirection"] == "R" else -1.0
            self._bs.traf.ap.selhdgcmd(
                index, (float(self._bs.traf.hdg[index])
                        + direction * turn_rate * 2.0) % 360.0)

    def _update_vor_intercepts(self) -> None:
        for callsign, vor in self._vor_executions.items():
            if vor["established"]:
                continue
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                continue
            lat = float(self._bs.traf.lat[index])
            lon = float(self._bs.traf.lon[index])
            bearing_to_station, distance_nm = self._qdrdist_nm(
                lat, lon, vor["stationLat"], vor["stationLon"])
            # 径向 = 电台→航空器方位（航空器所在径向线）；截获 = 位于目标径向±3°
            aircraft_radial = (bearing_to_station + 180.0) % 360.0
            radial_error = (aircraft_radial - vor["radialDeg"] + 180.0) % 360.0 - 180.0
            if abs(radial_error) <= 3.0 and distance_nm <= 30.0:
                vor["established"] = True
                continue
            # 朝电台飞并向目标径向线修正（±30° 截获角）
            correction = max(-30.0, min(30.0, radial_error))
            self._bs.traf.swlnav[index] = False
            self._bs.traf.ap.selhdgcmd(
                index, (bearing_to_station + correction) % 360.0)

    def _update_ils_approaches(self) -> None:
        for callsign, approach in self._ils_approaches.items():
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                continue
            lat = float(self._bs.traf.lat[index])
            lon = float(self._bs.traf.lon[index])
            bearing, distance_nm = self._qdrdist_nm(
                lat, lon, approach["airportLat"], approach["airportLon"])
            course_error = (bearing - approach["finalHdg"] + 180.0) % 360.0 - 180.0
            approach_side_nm = distance_nm * cos(radians(course_error))
            # 两段引导自愈：不依赖航路点自动切换（过点后 LNAV 可能直接
            # 断开或提前跳到末点）。距 FAF≤2.5NM 单向锁存提交五边——
            # 锁存前强制直飞 FAF，锁存后强制直飞机场点，任何复位/跳点
            # 都在下一 tick 被纠正；锁存避免门限两侧目标翻转成极限环
            if not approach["landed"]:
                _, faf_distance_nm = self._qdrdist_nm(
                    lat, lon, approach["fafLat"], approach["fafLon"])
                _, final_leg_nm = self._qdrdist_nm(
                    approach["fafLat"], approach["fafLon"], approach["airportLat"], approach["airportLon"])
                route = self._bs.traf.ap.route[index]
                active = (str(route.wpname[route.iactwp])
                          if 0 <= route.iactwp < route.nwp else "")
                already_on_final = (0.0 < approach_side_nm <= final_leg_nm
                                    and abs(course_error) <= 10.0)
                if not approach.get("fafPassed") and (faf_distance_nm <= 2.5 or already_on_final):
                    approach["fafPassed"] = True
                target_wp = (approach["apWp"]
                             if approach.get("fafPassed") else approach["fafWp"])
                if active != target_wp or not self._bs.traf.swlnav[index]:
                    try:
                        route.direct(index, target_wp)
                        self._bs.traf.swlnav[index] = True
                    except ValueError:
                        pass
            # 引导由 LNAV 航段（ILSFAF→ILSAP）保证；控制器只做截获/落地监控
            if (not approach["lateralCaptured"] and approach_side_nm >= 3.0
                    and abs(course_error) <= 10.0 and distance_nm <= 20.0):
                approach["lateralCaptured"] = True
            if (approach["lateralCaptured"]
                    and not approach["verticalCaptured"]
                    and approach_side_nm >= 0.0 and distance_nm <= 12.0):
                approach["verticalCaptured"] = True
                self._bs.traf.ap.selaltcmd(index, approach.get("elevationFt",0.0) * ft)
                self._bs.traf.ap.selspdcmd(index, 160.0 * kts)
            if (not approach["landed"] and approach["verticalCaptured"]
                    and distance_nm <= 0.5 and abs(course_error) <= 10.0
                    and float(self._bs.traf.alt[index]) / ft <= approach.get("elevationFt", 0.0) + 5.0):
                approach["landed"] = True
                self._bs.traf.swlnav[index] = False
                self._bs.traf.ap.selaltcmd(index, approach.get("elevationFt", 0.0) * ft)
                self._bs.traf.ap.selspdcmd(index, 0.0)
                self._emit_flight_event(callsign, "LANDED",
                                        "landed:" + approach["commandId"],
                                        "进近落地")

    def _update_missed_receipts(self) -> None:
        for callsign, receipt in self._missed_receipts.items():
            index = self._bs.traf.id2idx(callsign)
            if index < 0:
                continue
            altitude_ft = float(self._bs.traf.alt[index]) / ft
            if not receipt["climbEstablished"] and (
                    altitude_ft >= receipt["startAltFt"] + 500.0
                    and self._bs.traf.vs[index] > 0.0):
                receipt["climbEstablished"] = True
                if not receipt["missedEventEmitted"]:
                    receipt["missedEventEmitted"] = True
                    self._emit_flight_event(callsign, "MISSED_APPROACH",
                                            "missed:" + receipt["commandId"],
                                            "复飞爬升建立")

    def _emit_flight_event(self, callsign, event_type, source_event_id, detail) -> None:
        self._flight_events.setdefault(callsign, []).append({
            "type": event_type,
            "sourceEventId": source_event_id,
            "detail": detail,
        })

    def _qdrdist_nm(self, lat1, lon1, lat2, lon2):
        from bluesky.tools.geo import qdrdist
        qdr, dist = qdrdist(lat1, lon1, lat2, lon2)
        return (float(qdr) % 360.0, float(dist))

    @staticmethod
    def _offset_position(lat, lon, bearing_deg, distance_nm):
        """从 (lat, lon) 沿方位 distance_nm 海里的新位置（大圆近似）。"""
        from math import radians, cos, sin, asin, atan2, degrees
        angular = distance_nm / 3440.065
        lat_rad = radians(lat)
        lon_rad = radians(lon)
        bearing = radians(bearing_deg % 360.0)
        new_lat = asin(sin(lat_rad) * cos(angular)
                       + cos(lat_rad) * sin(angular) * cos(bearing))
        new_lon = lon_rad + atan2(sin(bearing) * sin(angular) * cos(lat_rad),
                                  cos(angular) - sin(lat_rad) * sin(new_lat))
        return (degrees(new_lat), degrees(new_lon))

    def _require_command_id(self, payload, instruction_type) -> str:
        command_id = str(payload.get("commandId", "")).strip()
        if not command_id:
            raise ValueError("{} 缺少 commandId".format(instruction_type))
        return command_id

    def _v2_parameters(self, payload) -> Dict[str, Any]:
        import json
        raw = payload.get("parametersJson")
        if not raw:
            return {}
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {}
        except (TypeError, ValueError):
            return {}

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
        origin_point=self._reference_points[origin]
        elevation=float(origin_point.get("elevationMeters") or 0.0)
        _,distance=self._qdrdist_nm(latitude,longitude,origin_point["latitude"],origin_point["longitude"])
        ground=(abs(float(payload["altitudeFeet"])*ft-elevation)<=10.0 and distance<=2.0 and 0<=float(payload["speedKnots"])<40)
        if not ground:
            self._validate_initial_performance(aircraft_type,float(payload["altitudeFeet"])*ft,float(payload["speedKnots"])*kts)
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
            if ground:
                self._bs.traf.swlnav[index]=False
                self._bs.traf.ap.selaltcmd(index,float(payload["altitudeFeet"])*ft)
                self._bs.traf.ap.selspdcmd(index,float(payload["speedKnots"])*kts)
            self._managed_speeds[callsign] = float(self._bs.traf.cas[self._bs.traf.id2idx(callsign)])
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
            "flightPhase": self._flight_phase(index, str(callsign).upper()),
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
        receipts = self._guidance_receipts(str(callsign).upper())
        if receipts:
            snapshot["receipts"] = receipts
        pending_events = self._flight_events.get(str(callsign).upper())
        if pending_events:
            snapshot["events"] = list(pending_events)
            self._flight_events[str(callsign).upper()] = []
        performance_envelope = self._performance_envelope_snapshot(index)
        if performance_envelope is not None:
            snapshot["performanceEnvelope"] = performance_envelope
        return snapshot

    def _flight_phase(self, index, callsign):
        ils=self._ils_approaches.get(callsign)
        if ils:
            if ils["landed"]:
                return "ROLLOUT" if float(self._bs.traf.cas[index]) > 0.01 else "LANDED"
            return "FINAL" if ils["verticalCaptured"] else "APPROACH"
        takeoff=self._takeoff_receipts.get(callsign)
        if takeoff and not takeoff["airborne"]: return "TAKEOFF_ROLL"
        origin=str(self._bs.traf.ap.orig[index])
        elevation=float(self._reference_points.get(origin,{}).get("elevationMeters") or 0.0)
        if float(self._bs.traf.alt[index]) <= elevation + 10.0 and float(self._bs.traf.cas[index]) < 40*kts:
            return "PRE_DEPARTURE"
        vs=float(self._bs.traf.vs[index])/(ft/60.0)
        return "CLIMB" if vs>150 else "DESCENT" if vs < -150 else "CRUISE"

    def _guidance_receipts(self, callsign: str) -> Dict[str, Any]:
        receipts = {}
        takeoff = self._takeoff_receipts.get(callsign)
        if takeoff is not None:
            receipts["takeoff"] = {
                "commandId": takeoff["commandId"],
                "airborne": takeoff["airborne"],
                "targetHeadingDeg": takeoff["targetHeadingDeg"],
                "targetSpeedKt": takeoff["targetSpeedKt"],
            }
        hold = self._hold_executions.get(callsign)
        if hold is not None and hold["established"]:
            receipts["holdEstablished"] = {"commandId": hold["commandId"]}
        heading = self._heading_receipts.get(callsign)
        if heading is not None:
            receipts["headingExecuted"] = {
                "commandId": heading["commandId"],
                "targetHeadingDeg": heading["targetHeadingDeg"],
            }
        orbit = self._orbit_executions.get(callsign)
        if orbit is not None:
            receipts["orbitActive"] = {"commandId": orbit["commandId"]}
        vor = self._vor_executions.get(callsign)
        if vor is not None and vor["established"]:
            receipts["vorEstablished"] = {"commandId": vor["commandId"]}
        ils = self._ils_approaches.get(callsign)
        if ils is not None:
            receipts["ils"] = {
                "commandId": ils["commandId"],
                "lateralCaptured": ils["lateralCaptured"],
                "verticalCaptured": ils["verticalCaptured"],
                "landed": ils["landed"],
            }
        missed = self._missed_receipts.get(callsign)
        if missed is not None:
            receipts["missed"] = {
                "commandId": missed["commandId"],
                "climbEstablished": missed["climbEstablished"],
                "lateralEngaged": missed["lateralEngaged"],
            }
        simple = self._simple_receipts.get(callsign)
        if simple:
            receipts.update(simple)
        return receipts

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
        self._clear_controllers(normalized)
        return {"callsign": normalized, "deleted": True}

    def _clear_controllers(self, callsign=None):
        for name in ("_takeoff_receipts", "_heading_receipts", "_hold_executions",
                     "_orbit_executions", "_vor_executions", "_ils_approaches",
                     "_missed_receipts", "_simple_receipts", "_flight_events",
                     "_active_instructions", "_direct_to_executions", "_route_change_receipts", "_offset_routes", "_managed_speeds"):
            values = getattr(self, name, {})
            if callsign is None:
                values.clear()
            else:
                values.pop(callsign, None)

    def cancel_instruction(self, callsign, instruction_id, channels):
        callsign = callsign.upper()
        index = self._bs.traf.id2idx(callsign)
        if index < 0:
            return
        active = self._active_instructions.get(callsign, {})
        for channel in channels:
            if active.get(channel) != instruction_id:
                continue  # A late cancel must not undo a newer replacement.
            if channel == "LATERAL":
                original = self._offset_routes.pop(callsign, None)
                if original:
                    route = self._bs.traf.ap.route[index]
                    if len(original[0]) == route.nwp:
                        route.wplat[:], route.wplon[:] = original
                        route.calcfp()
                self._bs.traf.swlnav[index] = False
                self._bs.traf.ap.selhdgcmd(index, float(self._bs.traf.hdg[index]))
            elif channel == "VERTICAL":
                self._bs.traf.ap.selaltcmd(index, float(self._bs.traf.alt[index]), 0.0)
            elif channel == "SPEED":
                self._bs.traf.ap.selspdcmd(index, float(self._bs.traf.cas[index]))
            active.pop(channel, None)
        for name in ("_hold_executions", "_orbit_executions", "_vor_executions",
                     "_ils_approaches", "_direct_to_executions", "_takeoff_receipts",
                     "_heading_receipts", "_missed_receipts", "_route_change_receipts"):
            values = getattr(self, name, {})
            if values.get(callsign, {}).get("commandId") == instruction_id:
                values.pop(callsign, None)

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
        elif instruction_type == "VS":
            # BlueSky 的垂直速度引导必须挂在高度目标上（纯 selvspd 不产生
            # 俯仰变化）：按升降方向配对 3000ft 缓冲高度层，VS 0 = 保持当前
            vs_fpm = float(payload["verticalSpeedFeetPerMinute"])
            current_alt_ft = float(self._bs.traf.alt[index]) / ft
            buffer_alt_ft = 3000.0 if vs_fpm > 0 else -3000.0 if vs_fpm < 0 else 0.0
            target_alt_ft = max(1000.0, min(41000.0, current_alt_ft + buffer_alt_ft))
            self._bs.traf.ap.selaltcmd(
                index, target_alt_ft * ft, vs_fpm * ft / 60.0)
        elif instruction_type in ("LEFT", "RIGHT"):
            # 相对/绝对转弯：RELATIVE 以执行时刻航向为基准（解析载荷带 mode/valueDeg）；
            # 目标航向随回执上报，评估器不得用状态帧首航向猜基准（8x 下帧间隔
            # 内航空器已转出数度）
            params = self._v2_parameters(payload)
            mode = str(params.get("mode") or "RELATIVE").upper()
            value_deg = float(params.get("valueDeg") or 0.0)
            if mode == "ABSOLUTE":
                target_heading = value_deg % 360.0
            else:
                sign = -1.0 if instruction_type == "LEFT" else 1.0
                target_heading = (
                    float(self._bs.traf.hdg[index]) + sign * value_deg) % 360.0
            self._bs.traf.swlnav[index] = False
            self._bs.traf.ap.selhdgcmd(index, target_heading)
            self._direct_to_executions.pop(callsign, None)
            self._heading_receipts[callsign] = {
                "commandId": self._require_command_id(payload, instruction_type),
                "targetHeadingDeg": target_heading % 360.0,
            }
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
        elif instruction_type == "TAKEOFF":
            command_id = self._require_command_id(payload, "TAKEOFF")
            params = self._v2_parameters(payload)
            target_altitude = float(params.get("targetAltitudeFtMsl")
                                    or payload.get("altitudeFeet") or 10000.0)
            # Accelerate along the published runway, then climb under native performance limits.
            self._bs.traf.ap.selaltcmd(index, target_altitude * ft)
            self._bs.traf.ap.selspdcmd(index, 250.0 * kts)
            heading=float(params.get("trueHeadingDeg",self._bs.traf.hdg[index]))%360.0
            self._bs.traf.ap.selhdgcmd(index,heading)
            self._bs.traf.swlnav[index] = False
            self._takeoff_receipts[callsign] = {
                "commandId": command_id,
                "targetHeadingDeg": heading,
                "elevationFt": float(params.get("elevationFt",0.0)),
                "targetSpeedKt": 250.0,
                "airborne": False,
                "takeoffEventEmitted": False,
            }
        elif instruction_type == "SIDSTAR":
            command_id = self._require_command_id(payload, "SIDSTAR")
            points = self._v2_parameters(payload).get("route")
            if not points:
                raise ValueError("SIDSTAR 缺少已解析程序航路")
            self._validate_route(points)
            self._replace_route(index, points)
            self._route_change_receipts[callsign] = {
                "commandId": command_id,
                "activated": True,
            }
        elif instruction_type == "RESUME":
            command_id = self._require_command_id(payload, "RESUME")
            params = self._v2_parameters(payload)
            route = self._bs.traf.ap.route[index]
            waypoint = self._point_code(params.get("resumePoint") or payload.get("waypoint") or "")
            if not waypoint or waypoint == "AUTO":
                waypoint = self._forward_route_point(index)
            self._resolve_reference_point(waypoint)
            if waypoint not in route.wpname:
                raise ValueError("恢复点不在当前航路中: {}".format(waypoint))
            route.direct(index, waypoint)
            self._bs.traf.swlnav[index] = True
            self._direct_to_executions[callsign] = {
                "commandId": command_id,
                "waypoint": waypoint,
                "targetIndex": route.iactwp,
                "passed": False,
            }
        elif instruction_type == "HOLD":
            command_id = self._require_command_id(payload, "HOLD")
            params = self._v2_parameters(payload)
            if str(params.get("action", "FIX_AND_LEG")).upper() in ("CLEAR", "EXIT"):
                self._hold_executions.pop(callsign, None)
                self._resume_route(index)
                self._simple_receipts.setdefault(callsign, {})["holdEstablished"] = {"commandId": command_id}
            else:
                fix = self._point_code(params.get("fixPoint") or "")
                point = self._resolve_reference_point(fix)
                inbound = float(params.get("inboundMagneticHeadingDeg") or 0.0) % 360.0
                self._hold_executions[callsign] = {
                    "commandId": command_id,
                    "fix": fix,
                    "fixLat": point["latitude"],
                    "fixLon": point["longitude"],
                    "turnDirection": str(params.get("turnDirection") or "R").upper(),
                    "inboundHdg": inbound,
                    "legSeconds": float(params.get("legSeconds") or
                                        float(params.get("legNm", 0)) / max(1.0, float(self._bs.traf.gs[index]) / kts) * 3600 or 60.0),
                    "phase": "DIRECTING",
                    "phaseSince": float(self._bs.sim.simt),
                    "established": False,
                }
                route = self._bs.traf.ap.route[index]
                lnav_direct = fix in route.wpname
                self._hold_executions[callsign]["lnavDirect"] = lnav_direct
                if lnav_direct:
                    route.direct(index, fix)
                    self._bs.traf.swlnav[index] = True
        elif instruction_type == "ORBIT":
            command_id = self._require_command_id(payload, "ORBIT")
            params = self._v2_parameters(payload)
            if str(params.get("action", "ENTER")).upper() == "EXIT":
                self._orbit_executions.pop(callsign, None)
                self._resume_route(index)
                self._simple_receipts.setdefault(callsign, {})["orbitActive"] = {"commandId": command_id}
            else:
                center = params.get("centerPoint")
                if center:
                    point = self._resolve_reference_point(center)
                    center_lat, center_lon = point["latitude"], point["longitude"]
                else:
                    center_lat = float(self._bs.traf.lat[index])
                    center_lon = float(self._bs.traf.lon[index])
                self._orbit_executions[callsign] = {
                    "commandId": command_id,
                    "centerLat": center_lat,
                    "centerLon": center_lon,
                    "turnDirection": str(params.get("turnDirection") or "R").upper(),
                    "radiusNm": float(params.get("radiusNm") or 5.0),
                }
        elif instruction_type == "OFFSET":
            command_id = self._require_command_id(payload, "OFFSET")
            params = self._v2_parameters(payload)
            route = self._bs.traf.ap.route[index]
            if str(params.get("action")).upper() == "CLEAR":
                original = self._offset_routes.get(callsign)
                if original:
                    route.wplat[:], route.wplon[:] = original
                    self._offset_routes.pop(callsign, None)
            else:
                if route.nwp < 1:
                    raise ValueError("OFFSET 需要活动航路")
                distance = float(params.get("distanceNm", 0))
                if not 1 <= distance <= 10 or params.get("side") not in ("L", "R"):
                    raise ValueError("OFFSET 方向或距离非法")
                original = self._offset_routes.get(callsign, (list(route.wplat), list(route.wplon)))
                shifted_lat, shifted_lon = list(original[0]), list(original[1])
                previous = (float(self._bs.traf.lat[index]), float(self._bs.traf.lon[index]))
                for wp in range(max(0, route.iactwp), route.nwp):
                    point = (original[0][wp], original[1][wp])
                    bearing, _ = self._qdrdist_nm(*previous, *point)
                    shifted_lat[wp], shifted_lon[wp] = self._offset_position(*point, bearing + (-90 if params["side"] == "L" else 90), distance)
                    previous = point
                self._offset_routes[callsign] = original
                route.wplat[:], route.wplon[:] = shifted_lat, shifted_lon
            route.calcfp()
            self._resume_route(index)
            self._simple_receipts.setdefault(callsign, {})["offsetApplied"] = {
                "commandId": command_id,
            }
        elif instruction_type == "VOR":
            command_id = self._require_command_id(payload, "VOR")
            params = self._v2_parameters(payload)
            station = self._point_code(params.get("station") or "")
            point = self._resolve_reference_point(station)
            self._vor_executions[callsign] = {
                "commandId": command_id,
                "station": station,
                "stationLat": point["latitude"],
                "stationLon": point["longitude"],
                "radialDeg": float(params.get("radialDeg") or 0.0) % 360.0,
                "established": False,
            }
        elif instruction_type in ("P_LEVEL", "P_TIME"):
            command_id = self._require_command_id(payload, instruction_type)
            params = self._v2_parameters(payload)
            route = self._bs.traf.ap.route[index]
            point = str(params.get("legPoint", "")).upper()
            if point not in route.wpname or route.wpname.index(point) < route.iactwp:
                raise ValueError("约束航路点不在未来航段中")
            wp = route.wpname.index(point)
            if instruction_type == "P_LEVEL":
                altitude = float(params["altitudeFtMsl"]) * ft
                self._validate_altitude_ceiling(index, altitude)
                route.wpalt[wp] = altitude
            else:
                target = float(params["targetTimeSeconds"])
                if target <= self.simulation_time():
                    raise ValueError("约束时刻已过期")
                route.wprta[wp] = target
            route.calcfp()
            self._resume_route(index)
            self._bs.traf.swvnav[index] = True
            self._bs.traf.swvnavspd[index] = True
            self._simple_receipts.setdefault(callsign, {})["planConstraintApplied"] = {
                "commandId": command_id,
            }
        elif instruction_type == "ILS":
            command_id = self._require_command_id(payload, "ILS")
            params = self._v2_parameters(payload)
            airport = self._point_code(params.get("airportCode") or "")
            self._resolve_reference_point(airport)
            point = {"latitude": float(params["thresholdLatitude"]), "longitude": float(params["thresholdLongitude"])}
            final_hdg = float(params["trueHeadingDeg"]) % 360.0
            faf_lat, faf_lon = self._offset_position(
                point["latitude"], point["longitude"], final_hdg + 180.0, 12.0)
            # 进近引导接管：终止等待/盘旋引导，避免双控制器争夺航向
            self._hold_executions.pop(callsign, None)
            self._orbit_executions.pop(callsign, None)
            # 引导用仿真原生 LNAV：把 FAF 与机场追加为航路点后直飞 FAF，
            # 截获与五边跟踪由航段引导完成（手搓航向律在近距必然振荡）
            route = self._bs.traf.ap.route[index]
            faf_idx = route.addwpt_simple(
                index, "ILSFAF", 0, faf_lat, faf_lon)
            ap_idx = route.addwpt_simple(
                index, "ILSAP", 0, point["latitude"], point["longitude"])
            route.direct(index, route.wpname[faf_idx])
            self._bs.traf.swlnav[index] = True
            self._ils_approaches[callsign] = {
                "commandId": command_id,
                "airport": airport,
                "airportLat": point["latitude"],
                "airportLon": point["longitude"],
                "finalHdg": final_hdg,
                "elevationFt": float(params["elevationFt"]),
                "fafLat": faf_lat,
                "fafLon": faf_lon,
                "fafWp": str(route.wpname[faf_idx]),
                "apWp": str(route.wpname[ap_idx]),
                "lateralCaptured": False,
                "verticalCaptured": False,
                "landed": False,
            }
        elif instruction_type == "MISSED":
            command_id = self._require_command_id(payload, "MISSED")
            points = self._v2_parameters(payload).get("route")
            if not points:
                raise ValueError("MISSED 缺少已解析复飞程序")
            self._validate_route(points)
            self._replace_route(index, points)
            current_altitude = float(self._bs.traf.alt[index]) / ft
            self._bs.traf.ap.selaltcmd(index, (current_altitude + 2500.0) * ft)
            if self._bs.traf.ap.route[index].nwp:
                self._bs.traf.swlnav[index] = True
            self._ils_approaches.pop(callsign, None)
            self._missed_receipts[callsign] = {
                "commandId": command_id,
                "startAltFt": current_altitude,
                "climbEstablished": False,
                "lateralEngaged": bool(self._bs.traf.ap.route[index].nwp),
                "missedEventEmitted": False,
            }
        elif instruction_type == "NSPEED":
            route = self._bs.traf.ap.route[index]
            target = (route.wpspd[route.iactwp] if 0 <= route.iactwp < route.nwp else -1)
            if target <= 0:
                target = self._managed_speeds.get(callsign)
                if target is None:
                    raise ValueError("缺少计划 managed speed")
            self._validate_speed_envelope(index, target)
            self._bs.traf.ap.selspdcmd(index, target)
            self._bs.traf.swvnavspd[index] = True
            self._simple_receipts.setdefault(callsign, {})["managedSpeedRestored"] = {"commandId": self._require_command_id(payload, "NSPEED"), "targetSpeedKt": target / kts}
        else:
            raise ValueError("不支持的指令类型: {}".format(instruction_type))

        lateral = instruction_type in ("HDG", "LEFT", "RIGHT", "DCT", "RTE", "RESUME",
                                       "HOLD", "ORBIT", "VOR", "ILS", "TAKEOFF", "MISSED", "OFFSET", "SIDSTAR")
        for kind, name in (("HOLD", "_hold_executions"), ("ORBIT", "_orbit_executions"),
                           ("VOR", "_vor_executions"), ("ILS", "_ils_approaches")):
            if (lateral and instruction_type != kind) or (kind == "ILS" and instruction_type in ("ALT", "VS", "SPD", "MACH")):
                getattr(self, name, {}).pop(callsign, None)
        channels = payload.get("affectedChannels") or (["LATERAL"] if lateral else
                   ["VERTICAL"] if instruction_type in ("ALT", "VS") else ["SPEED"])
        self._active_instructions.setdefault(callsign, {}).update(
            {channel: payload.get("commandId") for channel in channels})
        return result

    def _resume_route(self, index):
        route = self._bs.traf.ap.route[index]
        if route.nwp:
            route.direct(index, route.wpname[max(0, route.iactwp)])
            self._bs.traf.swlnav[index] = True

    def _forward_route_point(self, index):
        route = self._bs.traf.ap.route[index]
        heading = float(self._bs.traf.hdg[index])
        for wp in range(max(0, route.iactwp), route.nwp):
            bearing, _ = self._qdrdist_nm(float(self._bs.traf.lat[index]), float(self._bs.traf.lon[index]), route.wplat[wp], route.wplon[wp])
            if abs((bearing - heading + 180.0) % 360.0 - 180.0) <= 90.0:
                return str(route.wpname[wp])
        raise ValueError("当前航向前方没有可恢复的未来航段")

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
            self._offset_routes.pop(str(self._bs.traf.id[aircraft_index]), None)
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
