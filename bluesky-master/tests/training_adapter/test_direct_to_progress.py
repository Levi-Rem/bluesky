import unittest
from types import SimpleNamespace

from bluesky.plugins.training_adapter.engine import BlueSkyEngine


class _Traffic:
    def __init__(self, route, lnav=True):
        self.ap = SimpleNamespace(route=[route])
        self.swlnav = [lnav]

    @staticmethod
    def id2idx(callsign):
        return 0 if callsign == "CCA3582" else -1


class DirectToProgressTest(unittest.TestCase):
    def _engine(self, active_index, target_index, waypoint_count=3, lnav=True):
        engine = object.__new__(BlueSkyEngine)
        route = SimpleNamespace(iactwp=active_index, nwp=waypoint_count)
        engine._bs = SimpleNamespace(traf=_Traffic(route, lnav))
        engine._direct_to_executions = {
            "CCA3582": {
                "commandId": "command-1",
                "waypoint": "CEN",
                "targetIndex": target_index,
                "passed": False,
            }
        }
        return engine

    def test_target_becoming_active_does_not_complete_direct_to(self):
        engine = self._engine(active_index=1, target_index=1)

        engine._observe_direct_to_progress()

        self.assertFalse(engine._direct_to_executions["CCA3582"]["passed"])

    def test_switching_to_later_waypoint_marks_direct_to_passed(self):
        engine = self._engine(active_index=2, target_index=1)

        engine._observe_direct_to_progress()

        self.assertTrue(engine._direct_to_executions["CCA3582"]["passed"])

    def test_last_waypoint_passed_when_lnav_switches_off(self):
        engine = self._engine(
            active_index=2, target_index=2, waypoint_count=3, lnav=False
        )

        engine._observe_direct_to_progress()

        self.assertTrue(engine._direct_to_executions["CCA3582"]["passed"])


if __name__ == "__main__":
    unittest.main()
