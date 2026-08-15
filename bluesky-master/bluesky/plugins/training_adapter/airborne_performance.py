"""Read-only airborne performance curves exported for the training adapter."""

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class AirborneEnvelope:
    source_aircraft_type: str
    minimum_cas_mps: float
    maximum_cas_mps: float
    maximum_vertical_rate_mps: float


class AirbornePerformanceCatalog:
    """Small public boundary hiding the exported curve representation."""

    def __init__(self, document):
        self.source_database = document["source"]["database"]
        self.scope = document["selection"]["scope"]
        self.load_type = document["selection"]["loadType"]
        self._aircraft = document["aircraft"]

    @property
    def aircraft_types(self):
        return tuple(sorted(self._aircraft))

    def source_aircraft_type(self, aircraft_type):
        return self._aircraft[str(aircraft_type).upper()]["sourceAircraftType"]

    def ceiling_meters(self, aircraft_type):
        return self._aircraft[str(aircraft_type).upper()]["ceilingMeters"]

    def supports(self, aircraft_type):
        return str(aircraft_type).upper() in self._aircraft

    @classmethod
    def load_default(cls):
        path = Path(__file__).with_name("data") / "airborne_performance.json"
        with path.open("r", encoding="utf-8") as stream:
            return cls(json.load(stream))

    def envelope(self, aircraft_type, altitude_meters, phase):
        normalized_type = str(aircraft_type).upper()
        normalized_phase = str(phase).upper()
        profile = self._aircraft[normalized_type]
        if float(altitude_meters) > profile["ceilingMeters"]:
            raise ValueError(
                "{} 超过性能库升限 {:.0f} m".format(
                    normalized_type, profile["ceilingMeters"]
                )
            )
        rows = profile["phases"][normalized_phase]
        lower, upper = self._surrounding_rows(rows, float(altitude_meters))
        lower_altitude = lower["altitudeMeters"]
        upper_altitude = upper["altitudeMeters"]
        ratio = 0.0 if upper_altitude == lower_altitude else (
            (float(altitude_meters) - lower_altitude)
            / (upper_altitude - lower_altitude)
        )

        def interpolate(field):
            return lower[field] + ratio * (upper[field] - lower[field])

        return AirborneEnvelope(
            source_aircraft_type=normalized_type,
            minimum_cas_mps=interpolate("minimumCasMetersPerSecond"),
            maximum_cas_mps=interpolate("maximumCasMetersPerSecond"),
            maximum_vertical_rate_mps=interpolate(
                "maximumVerticalRateMetersPerSecond"
            ),
        )

    @staticmethod
    def _surrounding_rows(rows, altitude_meters):
        if altitude_meters <= rows[0]["altitudeMeters"]:
            return rows[0], rows[0]
        if altitude_meters >= rows[-1]["altitudeMeters"]:
            return rows[-1], rows[-1]
        for upper_index in range(1, len(rows)):
            upper = rows[upper_index]
            if altitude_meters <= upper["altitudeMeters"]:
                return rows[upper_index - 1], upper
        return rows[-1], rows[-1]
