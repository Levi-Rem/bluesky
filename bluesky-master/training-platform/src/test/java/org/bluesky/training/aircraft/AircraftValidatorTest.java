package org.bluesky.training.aircraft;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P07：呼号/ICAO24/八进制 Squawk 校验（详细设计 5.2.2、7.6）。 */
class AircraftValidatorTest {

    @Test
    void givenMixedCaseCallsignWhenNormalizedThenUppercase() {
        assertEquals("CSN3582", AircraftValidator.normalizeCallsign("csn3582"));
        assertEquals("CSN3582", AircraftValidator.normalizeCallsign("  CSN3582  "));
    }

    @Test
    void givenIllegalCallsignsWhenNormalizedThenRejected() {
        assertRejectsCallsign(null);
        assertRejectsCallsign("");
        assertRejectsCallsign("C");              // 太短
        assertRejectsCallsign("CSN-3582");       // 非法字符
        assertRejectsCallsign("CSN3582TOOLONG"); // 超过 12 位
        assertRejectsCallsign("国航3582");
    }

    private static void assertRejectsCallsign(String raw) {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> AircraftValidator.normalizeCallsign(raw));
        assertEquals("INVALID_INSTRUCTION", failure.code());
    }

    @Test
    void givenIcao24WhenValidatedThenHexOnly() {
        AircraftValidator.validateIcao24("780ABC");
        AircraftValidator.validateIcao24("780abc");
        AircraftValidator.validateIcao24(null);

        assertThrows(V2DomainException.class, () -> AircraftValidator.validateIcao24("780AB"));
        assertThrows(V2DomainException.class, () -> AircraftValidator.validateIcao24("780ABG"));
        assertThrows(V2DomainException.class, () -> AircraftValidator.validateIcao24("780ABCD"));
    }

    @Test
    void givenOctalSquawksWhenValidatedThenLeadingZerosKeptAndRangeEnforced() {
        AircraftValidator.validatePlannedSquawk("0042");
        AircraftValidator.validatePlannedSquawk("7777");
        AircraftValidator.validatePlannedSquawk("0001");

        // 8/9 非法
        V2DomainException eight = assertThrows(V2DomainException.class,
                () -> AircraftValidator.validatePlannedSquawk("0048"));
        V2DomainException nine = assertThrows(V2DomainException.class,
                () -> AircraftValidator.validatePlannedSquawk("0091"));
        assertEquals("INVALID_INSTRUCTION", eight.code());
        assertEquals("INVALID_INSTRUCTION", nine.code());

        // 长度
        assertThrows(V2DomainException.class, () -> AircraftValidator.validatePlannedSquawk("042"));
        assertThrows(V2DomainException.class, () -> AircraftValidator.validatePlannedSquawk("00042"));

        // 0000 明确拒绝
        V2DomainException zero = assertThrows(V2DomainException.class,
                () -> AircraftValidator.validatePlannedSquawk("0000"));
        assertTrue(zero.getMessage().contains("0000"));
    }

    @Test
    void givenDuplicateSquawkWhenCollectedThenWarningWithoutRejection() {
        Map<String, Object> warnings = AircraftValidator.collectWarnings(true);
        assertEquals("DUPLICATE_SQUAWK", warnings.get("warningCode"));

        assertTrue(AircraftValidator.collectWarnings(false).isEmpty());
    }

    @Test
    void givenInitialStateWhenValidatedThenRangesEnforced() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("latitudeDeg", 23.39);
        state.put("longitudeDeg", 113.29);
        state.put("trueHeadingDeg", 20.0);
        state.put("altitudeFtMsl", 50.0);
        state.put("indicatedAirspeedKt", 0.0);
        AircraftValidator.validateInitialState(state);

        state.put("latitudeDeg", 91.0);
        assertThrows(V2DomainException.class, () -> AircraftValidator.validateInitialState(state));
        state.put("latitudeDeg", 23.0);
        state.put("trueHeadingDeg", 361.0);
        assertThrows(V2DomainException.class, () -> AircraftValidator.validateInitialState(state));
        state.put("trueHeadingDeg", 20.0);
        assertThrows(V2DomainException.class, () -> AircraftValidator.validateInitialState(null));
    }
}
