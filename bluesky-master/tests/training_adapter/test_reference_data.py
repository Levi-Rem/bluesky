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

        self.assertTrue(result["accepted"])
        self.assertEqual(2, result["totalCount"])
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

    def test_rejects_duplicate_ids_invalid_types_coordinates_and_elevation_atomically(self):
        original = {"OLD": {"id": "old", "code": "OLD", "type": "WAYPOINT",
                            "latitude": 30.0, "longitude": 120.0}}
        invalid_batches = [
            [
                {"id": "same", "code": "P1", "type": "VOR", "latitude": 1, "longitude": 2},
                {"id": "same", "code": "P2", "type": "NDB", "latitude": 2, "longitude": 3},
            ],
            [{"id": "1", "code": "P1", "type": "OTHER", "latitude": 1, "longitude": 2}],
            [{"id": "1", "code": "P1", "type": "VOR", "latitude": True, "longitude": 2}],
            [{"id": "1", "code": "P1", "type": "VOR", "latitude": 91, "longitude": 2}],
            [{"id": "1", "code": "P1", "type": "VOR", "latitude": 1, "longitude": 2,
              "elevationMeters": {"bad": True}}],
        ]
        for points in invalid_batches:
            self.engine._reference_points = original
            with self.assertRaises(ValueError):
                self.engine.sync_reference_data({"points": points})
            self.assertIs(original, self.engine._reference_points)

    def test_resolved_object_normalizes_identity_and_tolerates_json_float_rounding(self):
        self.engine.sync_reference_data({"points": [
            {"id": "nav-1", "code": "PUD", "type": "VOR",
             "latitude": 31.1, "longitude": 121.8},
        ]})

        point = self.engine._resolve_reference_point(
            {"id": " nav-1 ", "code": "pud", "type": " vor ",
             "latitude": 31.1000000001, "longitude": 121.8000000001})

        self.assertEqual("PUD", point["code"])


if __name__ == "__main__":
    unittest.main()
