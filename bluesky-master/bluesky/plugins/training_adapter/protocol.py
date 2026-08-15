"""Versioned JSON message handling for the training platform adapter."""

from typing import Any, Dict


PROTOCOL_VERSION = "1.0"


class AdapterProtocol:
    """Dispatches validated platform requests to an engine boundary."""

    def __init__(self, engine: Any):
        self._engine = engine

    def handle(self, request: Dict[str, Any]) -> Dict[str, Any]:
        request_id = str(request.get("requestId", ""))
        if request.get("protocolVersion") != PROTOCOL_VERSION:
            return self._failure(
                request_id,
                "UNSUPPORTED_PROTOCOL_VERSION",
                "仅支持协议版本 1.0",
            )

        try:
            return self._dispatch(request_id, request)
        except ValueError as exception:
            return self._failure(request_id, "ENGINE_REJECTED", str(exception))
        except Exception as exception:
            return self._failure(request_id, "ENGINE_ERROR", str(exception))

    def _dispatch(
        self, request_id: str, request: Dict[str, Any]
    ) -> Dict[str, Any]:
        message_type = request.get("type")
        if message_type == "PING":
            return self._success(request_id, self._engine.health())

        lifecycle_operations = {
            "START": self._engine.start,
            "PAUSE": self._engine.pause,
            "RESUME": self._engine.resume,
        }
        operation = lifecycle_operations.get(message_type)
        if operation is not None:
            operation()
            return self._success(request_id, {"engineState": message_type})

        if message_type == "RESET":
            return self._success(request_id, self._engine.reset())

        if message_type == "AIRCRAFT_CREATE":
            return self._success(
                request_id, self._engine.create_aircraft(request.get("payload") or {})
            )

        if message_type == "AIRCRAFT_DELETE":
            payload = request.get("payload") or {}
            return self._success(
                request_id, self._engine.delete_aircraft(str(payload.get("callsign", "")))
            )

        if message_type == "SNAPSHOT_GET":
            return self._success(request_id, self._engine.snapshot())

        if message_type == "INSTRUCTION_EXECUTE":
            return self._success(
                request_id,
                self._engine.execute_instruction(request.get("payload") or {}),
            )

        if message_type == "REFERENCE_SEARCH":
            return self._success(
                request_id,
                self._engine.search_reference(request.get("payload") or {}),
            )

        return self._failure(
            request_id,
            "UNSUPPORTED_MESSAGE_TYPE",
            "不支持的消息类型: {}".format(message_type),
        )

    @staticmethod
    def _success(request_id: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "requestId": request_id,
            "success": True,
            "code": "OK",
            "message": "",
            "payload": payload,
        }

    @staticmethod
    def _failure(request_id: str, code: str, message: str) -> Dict[str, Any]:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "requestId": request_id,
            "success": False,
            "code": code,
            "message": message,
            "payload": {},
        }
