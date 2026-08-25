import unittest

from bluesky.plugins.training_adapter.engine import BlueSkyEngine


class RuntimeReferenceDataTest(unittest.TestCase):
    def setUp(self):
        self.engine = object.__new__(BlueSkyEngine)
        self.engine._reference_points = {}

    def test_sync_replaces_catalog_and_returns_type_counts(self):
        result = self.engine.sync_reference_data({"points": [
            {"id": "nav-1", "code": "PUD", "type": "VOR",
             "latitude": 31.1, "longitude": 121.8},
            {"id": "apt-1", "code": "ZSSS", "type": "AIRPORT",
             "latitude": 31.2, "longitude": 121.3},
        ]})

        self.assertEqual(2, result["total"])
        self.assertEqual({"VOR": 1, "AIRPORT": 1}, result["counts"])
        self.assertEqual((31.1, 121.8), self.engine._resolve_position(
            {"initialWaypoint": "pud"}))

    def test_invalid_sync_does_not_replace_previous_catalog(self):
        original = {"OLD": {"id": "old", "code": "OLD", "type": "WAYPOINT",
                            "latitude": 30.0, "longitude": 120.0}}
        self.engine._reference_points = original

        with self.assertRaisesRegex(ValueError, "代码重复"):
            self.engine.sync_reference_data({"points": [
                {"id": "1", "code": "PUD", "type": "VOR",
                 "latitude": 31.1, "longitude": 121.8},
                {"id": "2", "code": "pud", "type": "WAYPOINT",
                 "latitude": 30.0, "longitude": 120.0},
            ]})

        self.assertIs(original, self.engine._reference_points)

    def test_resolved_point_object_must_match_synchronized_catalog(self):
        self.engine.sync_reference_data({"points": [
            {"id": "nav-1", "code": "PUD", "type": "VOR",
             "latitude": 31.1, "longitude": 121.8},
        ]})

        point = self.engine._resolve_reference_point(
            {"id": "nav-1", "code": "PUD", "type": "VOR",
             "latitude": 31.1, "longitude": 121.8})
        self.assertEqual("nav-1", point["id"])
        with self.assertRaisesRegex(ValueError, "不一致"):
            self.engine._resolve_reference_point(
                {"id": "wrong", "code": "PUD", "type": "VOR",
                 "latitude": 31.1, "longitude": 121.8})


if __name__ == "__main__":
    unittest.main()
