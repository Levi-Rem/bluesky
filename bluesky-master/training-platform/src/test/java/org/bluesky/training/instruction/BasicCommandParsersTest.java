package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P10：HDG/LEFT/RIGHT/ALT/VS/SPD/MACH 解析与规范化（详细设计 2.2 §7.1）。 */
class BasicCommandParsersTest {

    @Test
    void givenHeadingCommandsWhenParsedThenMagneticAndWrap360() {
        Map<String, Object> plain = BasicCommandParsers.parseHeading("HDG 090");
        assertEquals(90, plain.get("magneticHeadingDeg"));
        assertNull(plain.get("turnDirection"));

        Map<String, Object> left = BasicCommandParsers.parseHeading("HDG L 090");
        assertEquals("L", left.get("turnDirection"));

        Map<String, Object> wrapped = BasicCommandParsers.parseHeading("HDG 360");
        assertEquals(0, wrapped.get("magneticHeadingDeg"), "360 规范化为 000");

        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseHeading("HDG 361"));
        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseHeading("HDG abc"));
        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseHeading("HDG"));
    }

    @Test
    void givenTurnCommandsWhenParsedThenRelativeOrAbsoluteByDigitCount() {
        Map<String, Object> relative = BasicCommandParsers.parseTurn("LEFT 30");
        assertEquals("RELATIVE", relative.get("mode"));
        assertEquals(30, relative.get("valueDeg"));
        assertEquals("L", relative.get("turnDirection"));

        Map<String, Object> absolute = BasicCommandParsers.parseTurn("RIGHT 120");
        assertEquals("ABSOLUTE", absolute.get("mode"));
        assertEquals(120, absolute.get("valueDeg"));
        assertEquals("R", absolute.get("turnDirection"));

        // 相对角边界：1–99；恰好三位（100）按绝对磁航向解析
        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseTurn("LEFT 0"));
        assertEquals("ABSOLUTE", BasicCommandParsers.parseTurn("LEFT 100").get("mode"));
        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseTurn("LEFT abc"));
    }

    @Test
    void givenAltitudeCommandsWhenParsedThenUnitsNormalizeToFt() {
        Map<String, Object> meters = BasicCommandParsers.parseAltitude("ALT 9000M");
        assertEquals(29527.56, (double) meters.get("altitudeFtMsl"), 0.1,
                "9000m 必须经 UnitConverter 换算为英尺规范值");

        Map<String, Object> feet = BasicCommandParsers.parseAltitude("ALT 30000FT");
        assertEquals(30000.0, feet.get("altitudeFtMsl"));

        Map<String, Object> withVs = BasicCommandParsers.parseAltitude("ALT 30000FT VS 1000FPM");
        assertEquals(30000.0, withVs.get("altitudeFtMsl"));
        assertEquals(1000.0, withVs.get("verticalRateFpm"), "内嵌无符号 VS 为绝对值");

        Map<String, Object> flightLevel = BasicCommandParsers.parseAltitude("ALT FL200");
        assertEquals(20000.0, flightLevel.get("altitudeFtMsl"));

        // 三种单位输入产生一致规范值：30000FT == FL300 == 9144M
        double byFeet = (double) BasicCommandParsers.parseAltitude("ALT 30000FT").get("altitudeFtMsl");
        double byFl = (double) BasicCommandParsers.parseAltitude("ALT FL300").get("altitudeFtMsl");
        assertEquals(byFeet, byFl, 0.001);

        // 包线拒绝
        V2DomainException tooHigh = assertThrows(V2DomainException.class,
                () -> BasicCommandParsers.parseAltitude("ALT 70000FT"));
        assertEquals("PERFORMANCE_LIMIT_EXCEEDED", tooHigh.code());
    }

    @Test
    void givenVerticalSpeedCommandsWhenParsedThenSignRulesHold() {
        assertEquals(1000.0, BasicCommandParsers.parseVerticalSpeed("VS +1000FPM")
                .get("verticalRateFpm"));
        assertEquals(-984.25, (double) BasicCommandParsers.parseVerticalSpeed("VS -5MPS")
                .get("verticalRateFpm"), 0.1, "-5 m/s 换算为 fpm");
        assertEquals(0.0, BasicCommandParsers.parseVerticalSpeed("VS 0")
                .get("verticalRateFpm"));

        // 单独 VS 裸数拒绝（必须 +/- 或 0）
        V2DomainException bare = assertThrows(V2DomainException.class,
                () -> BasicCommandParsers.parseVerticalSpeed("VS 1000"));
        assertEquals("INVALID_INSTRUCTION", bare.code());

        // 包线
        assertThrows(V2DomainException.class,
                () -> BasicCommandParsers.parseVerticalSpeed("VS +7000FPM"));
    }

    @Test
    void givenSpeedAndMachWhenParsedThenIasAndDecimalRules() {
        assertEquals(269.978, (double) BasicCommandParsers.parseSpeed("SPD 500KMH")
                .get("indicatedAirspeedKt"), 0.1, "km/h 必须换算为 kt");
        assertEquals(280.0, BasicCommandParsers.parseSpeed("SPD 280KT")
                .get("indicatedAirspeedKt"));

        assertEquals(0.78, BasicCommandParsers.parseMach("MACH 0.78").get("mach"));
        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseMach("MACH 1.2"));
        assertThrows(V2DomainException.class, () -> BasicCommandParsers.parseMach("MACH 0,78"),
                "十进制分隔符只接受 '.'");

        assertThrows(V2DomainException.class,
                () -> BasicCommandParsers.parseSpeed("SPD 700KT"));
    }
}
