import unittest
from types import SimpleNamespace

from bluesky.plugins.training_adapter.airborne_performance import (
    AirbornePerformanceCatalog,
)
from bluesky.plugins.training_adapter.engine import BlueSkyEngine


class AirbornePerformanceCatalogTest(unittest.TestCase):
    def test_default_catalog_returns_a320_climb_envelope_at_database_height(self):
        catalog = AirbornePerformanceCatalog.load_default()

        envelope = catalog.envelope("A320", 6000.0, "CLIMB")

        self.assertEqual("simulator_backup", catalog.source_database)
        self.assertEqual("A320", envelope.source_aircraft_type)
        self.assertAlmostEqual(363.545 / 3.6, envelope.minimum_cas_mps, places=6)
        self.assertAlmostEqual(555.6 / 3.6, envelope.maximum_cas_mps, places=6)
        self.assertAlmostEqual(14.6304, envelope.maximum_vertical_rate_mps, places=6)

    def test_catalog_interpolates_between_height_levels_and_supports_descent(self):
        catalog = AirbornePerformanceCatalog.load_default()

        climb = catalog.envelope("a320", 6150.0, "climb")
        descent = catalog.envelope("A320", 6150.0, "DESCENT")

        self.assertAlmostEqual((14.6304 + 8.89) / 2.0,
                               climb.maximum_vertical_rate_mps, places=6)
        self.assertAlmostEqual(518.56 / 3.6, descent.maximum_cas_mps, places=6)
        self.assertAlmostEqual(17.78, descent.maximum_vertical_rate_mps, places=6)

    def test_default_catalog_contains_only_validated_openap_compatible_types(self):
        catalog = AirbornePerformanceCatalog.load_default()

        self.assertEqual(
            {"A319", "A320", "A321", "A21N", "A332", "A388", "B738", "B744", "B77W"},
            set(catalog.aircraft_types),
        )
        self.assertEqual("A321NEO", catalog.source_aircraft_type("A21N"))
        self.assertEqual("A380", catalog.source_aircraft_type("A388"))
        self.assertEqual("B773ER", catalog.source_aircraft_type("B77W"))

    def test_catalog_rejects_altitude_above_imported_ceiling(self):
        catalog = AirbornePerformanceCatalog.load_default()

        with self.assertRaisesRegex(ValueError, "升限"):
            catalog.envelope("A320", 12600.0, "CRUISE")

    def test_engine_handles_non_openap_models_without_coefficients(self):
        engine = object.__new__(BlueSkyEngine)
        engine._bs = SimpleNamespace(
            traf=SimpleNamespace(perf=SimpleNamespace())
        )

        self.assertEqual(set(), engine._supported_aircraft_types())

    def test_engine_exposes_only_fixed_wing_type_codes(self):
        engine = object.__new__(BlueSkyEngine)
        engine._bs = SimpleNamespace(
            traf=SimpleNamespace(perf=SimpleNamespace(coeff=SimpleNamespace(
                actypes_fixwing=["A320", "B738"],
                actypes_rotor=["Bob", "Echo", "Super"],
            )))
        )

        self.assertEqual({"A320", "B738"}, engine._supported_aircraft_types())


if __name__ == "__main__":
    unittest.main()
