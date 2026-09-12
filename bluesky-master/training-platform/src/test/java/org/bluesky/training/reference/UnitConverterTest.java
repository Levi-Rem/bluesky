package org.bluesky.training.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P03：唯一单位转换入口（详细设计 4.3 航空量值基准）。 */
class UnitConverterTest {

    @Test
    void givenExactFactorsWhenConvertingThenValuesAreCanonical() {
        assertEquals(0.3048, UnitConverter.feetToMeters(1.0), 1e-9);
        assertEquals(1.0, UnitConverter.metersToFeet(0.3048), 1e-9);
        assertEquals(1852.0 / 3600.0, UnitConverter.knotsToMetersPerSecond(1.0), 1e-9);
        assertEquals(0.3048 / 60.0, UnitConverter.fpmToMetersPerSecond(1.0), 1e-9);
        assertEquals(20000.0, UnitConverter.flightLevelToFeet(200), 1e-9);
        assertEquals(0.0, UnitConverter.flightLevelToFeet(0), 1e-9);
    }

    @Test
    void givenRoundTripsWhenConvertingThenErrorsStayNegligible() {
        for (double feet : new double[]{0.0, 50.0, 18000.0, 54000.0}) {
            double roundTrip = UnitConverter.metersToFeet(UnitConverter.feetToMeters(feet));
            assertEquals(feet, roundTrip, 1e-6);
        }
        for (double knots : new double[]{0.0, 140.0, 280.0}) {
            double roundTrip = UnitConverter.knotsToMetersPerSecond(knots) * 3600.0 / 1852.0;
            assertEquals(knots, roundTrip, 1e-9);
        }
    }

    @Test
    void givenThreeUnitInputsWhenNormalizedThenSameCanonicalValue() {
        double byFeet = UnitConverter.feetToMeters(30000.0);
        double byMeters = 30000.0 * 0.3048;
        double byFlightLevel = UnitConverter.feetToMeters(UnitConverter.flightLevelToFeet(300));
        assertEquals(byMeters, byFeet, 1e-9);
        assertEquals(byMeters, byFlightLevel, 1e-9);
    }

    @Test
    void givenNegativeFlightLevelWhenConvertedThenRejected() {
        assertThrows(IllegalArgumentException.class, () -> UnitConverter.flightLevelToFeet(-1));
    }
}
