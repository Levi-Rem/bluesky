"""Protocol 2.0 handlers backed by the existing isolated BlueSky engine boundary."""

import hashlib
import json
from math import isclose


class EngineV2Handlers(object):
    """Maps v2 message types to BlueSkyEngine calls and provides replay-safe aircraft apply."""

    def __init__(self, engine):
        self.engine = engine

    def handlers(self):
        return {
            "HELLO": self.hello,
            "REFERENCE_SNAPSHOT_LOAD": self.load_reference_snapshot,
            "START": self.start,
            "PAUSE": self.pause,
            "RESUME": self.resume,
            "STOP": self.stop,
            "RESET_DEMO_ONLY": self.reset,
            "AIRCRAFT_APPLY": self.apply_aircraft,
            "AIRCRAFT_EXISTS_GET": self.aircraft_exists,
            "AIRCRAFT_DELETE": self.delete_aircraft,
            "INSTRUCTION_APPLY": self.apply_instruction,
            "INSTRUCTION_CANCEL": self.cancel_instruction,
            "STATE_SNAPSHOT_GET": self.state_snapshot,
        }

    def hello(self, payload):
        result = dict(self.engine.health())
        result["accepted"] = bool(result.get("connected"))
        if "aircraftTypeQuery" in payload:
            result["aircraftTypes"] = self.engine.search_reference({
                "kind": "AIRCRAFT_TYPE", "query": payload["aircraftTypeQuery"], "limit": 50,
            })["items"]
        return result

    def load_reference_snapshot(self, payload):
        manifest_json = payload.get("manifestJson")
        if not isinstance(manifest_json, str) or not manifest_json:
            raise ValueError("缺少 manifestJson")
        actual = hashlib.sha256(manifest_json.encode("utf-8")).hexdigest()
        expected = payload.get("manifestChecksum")
        if expected and str(expected) != actual:
            raise ValueError("manifest checksum 不匹配")
        points = self._reference_points(payload.get("resources") or {})
        sync = self.engine.sync_reference_data({"points": points})
        return {
            "accepted": True,
            "manifestChecksum": actual,
            "resourceCount": len(payload.get("resources") or {}),
            "pointCount": sync.get("totalCount", len(points)),
        }

    def start(self, _payload):
        self.engine.start()
        return self._engine_state("RUNNING")

    def pause(self, _payload):
        self.engine.pause()
        snapshot = self.engine.snapshot()
        # PAUSED 必须携带实际暂停时刻（详细设计 4.2.7；评审 A5）：
        # Java 侧以该值落库 simulation_time_seconds
        return {
            "accepted": True,
            "engineState": "PAUSED",
            "simulationTimeSeconds": snapshot.get("simulationTimeSeconds", 0.0),
            "actualPauseSimulationTimeSeconds": snapshot.get("simulationTimeSeconds", 0.0),
        }

    def resume(self, _payload):
        self.engine.resume()
        return self._engine_state("RUNNING")

    def stop(self, _payload):
        self.engine.stop()
        return self._engine_state("STOPPED")

    def reset(self, _payload):
        return self.engine.reset()

    def apply_aircraft(self, payload):
        callsign = self._required(payload, "callsign").upper()
        if self._exists(callsign):
            snapshot = self.engine.aircraft_snapshot(callsign)
            self._assert_same_aircraft(snapshot, payload)
            return {"accepted": True, "aircraftId": payload.get("aircraftId"),
                    "alreadyExisted": True, "aircraft": snapshot}
        snapshot = self.engine.create_aircraft(payload)
        return {"accepted": True, "aircraftId": payload.get("aircraftId"),
                "alreadyExisted": False, "aircraft": snapshot}

    def aircraft_exists(self, payload):
        callsign = self._required(payload, "callsign").upper()
        if not self._exists(callsign):
            return {"accepted": True, "exists": False,
                    "aircraftId": payload.get("aircraftId")}
        return {"accepted": True, "exists": True,
                "aircraftId": payload.get("aircraftId"),
                "aircraft": self.engine.aircraft_snapshot(callsign)}

    def delete_aircraft(self, payload):
        callsign = self._required(payload, "callsign")
        result = self.engine.delete_aircraft(callsign)
        result["accepted"] = True
        result["aircraftId"] = payload.get("aircraftId")
        return result

    def apply_instruction(self, payload):
        # v2 names stay on the wire; translate once at the legacy engine boundary.
        command = dict(payload)
        parameters = payload.get("parameters") or {}
        if not isinstance(parameters, dict):
            raise ValueError("parameters 必须是对象")
        command["commandId"] = self._required(payload, "instructionId")
        command["parametersJson"] = json.dumps(parameters)
        for source, target in {
            "magneticHeadingDeg": "headingDegrees", "altitudeFtMsl": "altitudeFeet",
            "indicatedAirspeedKt": "speedKnots", "verticalRateFpm": "verticalSpeedFeetPerMinute",
            "mach": "mach", "targetPoint": "waypoint", "route": "route",
        }.items():
            if source in parameters:
                command[target] = parameters[source]
        result = self.engine.execute_instruction(command)
        result["instructionId"] = command["commandId"]
        return result

    def cancel_instruction(self, payload):
        self.engine.cancel_instruction(self._required(payload, "callsign"),
                                       self._required(payload, "instructionId"),
                                       payload.get("affectedChannels") or [])
        return {"accepted": True, "instructionId": payload["instructionId"]}

    def state_snapshot(self, _payload):
        result = self.engine.snapshot()
        result["accepted"] = True
        return result

    def _engine_state(self, state):
        snapshot = self.engine.snapshot()
        return {"accepted": True, "engineState": state,
                "simulationTimeSeconds": snapshot.get("simulationTimeSeconds", 0.0)}

    def _exists(self, callsign):
        try:
            self.engine.aircraft_snapshot(callsign)
            return True
        except ValueError as failure:
            if "不存在" in str(failure):
                return False
            raise

    @staticmethod
    def _assert_same_aircraft(snapshot, payload):
        checks = (("aircraftType", "aircraftType"),
                  ("latitude", "latitude"), ("longitude", "longitude"),
                  ("headingDegrees", "headingDegrees"),
                  ("altitudeFeet", "altitudeFeet"), ("speedKnots", "speedKnots"))
        for snapshot_key, payload_key in checks:
            if payload.get(payload_key) is None:
                continue
            actual = snapshot.get(snapshot_key)
            expected = payload.get(payload_key)
            if isinstance(expected, (int, float)):
                if actual is None or not isclose(float(actual), float(expected),
                                                 rel_tol=0.0, abs_tol=1e-6):
                    raise ValueError("同呼号航空器状态与重放请求不一致: " + payload_key)
            elif str(actual).upper() != str(expected).upper():
                raise ValueError("同呼号航空器状态与重放请求不一致: " + payload_key)

    @classmethod
    def _reference_points(cls, resources):
        points = []
        for resource_name in ("airports", "navaids"):
            entries = resources.get(resource_name) or []
            if isinstance(entries, dict):
                entries = entries.get("items") or entries.get("points") or []
            if not isinstance(entries, list):
                raise ValueError(resource_name + " 资源必须是数组")
            for raw in entries:
                if not isinstance(raw, dict):
                    raise ValueError(resource_name + " 条目必须是对象")
                code = str(raw.get("code") or raw.get("icao") or raw.get("id") or "").upper()
                point_type = "AIRPORT" if resource_name == "airports" else str(
                    raw.get("type") or "WAYPOINT").upper()
                points.append({
                    "id": str(raw.get("id") or code),
                    "code": code,
                    "type": point_type,
                    "latitude": cls._coordinate(raw, "latitude"),
                    "longitude": cls._coordinate(raw, "longitude"),
                    "elevationMeters": raw.get("elevationMeters"),
                })
        if not points:
            raise ValueError("参考快照不包含 airports/navaids 导航点")
        return points

    @staticmethod
    def _coordinate(raw, name):
        value = raw.get(name)
        if value is None:
            value = raw.get(name + "Deg")
        if value is None:
            raise ValueError("参考点缺少 " + name)
        return value

    @staticmethod
    def _required(payload, key):
        value = payload.get(key)
        if value is None or not str(value).strip():
            raise ValueError("缺少 " + key)
        return str(value).strip()
