package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P11：航路与导航命令解析（详细设计 2.2 §7.2）。 */
class NavigationCommandParsersTest {

    @Test
    void givenDirectToWhenParsedThenExitAnchorCaptured() {
        Map<String, Object> dct = NavigationCommandParsers.parseDirectTo("DCT LMN");
        assertEquals("LMN", dct.get("targetPoint"));
        assertEquals(Boolean.TRUE, dct.get("captureExitAnchor"),
                "航路外直飞必须保存退出锚点");

        assertThrows(V2DomainException.class, () -> NavigationCommandParsers.parseDirectTo("DCT"));
        assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseDirectTo("DCT 非法点!"));
    }

    @Test
    void givenRouteWhenParsedThenFullReplacementWithDestinationGuard() {
        Map<String, Object> rte = NavigationCommandParsers.parseRoute(
                "RTE ZGGG LMN P47 ZBAA", "ZBAA");
        assertEquals(Arrays.asList("ZGGG", "LMN", "P47", "ZBAA"), rte.get("route"));
        assertEquals("FULL_UNFLOWN", rte.get("replacementMode"));

        V2DomainException wrongEnd = assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseRoute("RTE ZGGG LMN", "ZBAA"));
        assertEquals("INVALID_INSTRUCTION", wrongEnd.code());

        assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseRoute("RTE", "ZBAA"));
    }

    @Test
    void givenResumeWhenParsedThenExplicitOrAutoMode() {
        Map<String, Object> explicit = NavigationCommandParsers.parseResume("RESUME LMN");
        assertEquals("LMN", explicit.get("resumePoint"));
        assertEquals("EXPLICIT", explicit.get("mode"));

        Map<String, Object> auto = NavigationCommandParsers.parseResume("RESUME");
        assertNull(auto.get("resumePoint"));
        assertEquals("AUTO", auto.get("mode"), "省略时由 Adapter 自动选择截获角 ≤90° 航段");

        assertThrows(V2DomainException.class, () -> NavigationCommandParsers.parseResume("RESUME A B"));
    }

    @Test
    void givenOrbitWhenParsedThenDirectionRadiusAndCenterVariants() {
        Map<String, Object> plain = NavigationCommandParsers.parseOrbit("ORBIT L");
        assertEquals("ENTER", plain.get("action"));
        assertEquals("L", plain.get("turnDirection"));
        assertEquals(5.0, plain.get("radiusNm"), "省略半径 5 NM");
        assertNull(plain.get("centerPoint"), "省略中心使用下发时位置");

        Map<String, Object> withCenter = NavigationCommandParsers.parseOrbit("ORBIT P47 R 12");
        assertEquals("P47", withCenter.get("centerPoint"));
        assertEquals(12.0, withCenter.get("radiusNm"));

        Map<String, Object> exit = NavigationCommandParsers.parseOrbit("ORBIT EXIT");
        assertEquals("EXIT", exit.get("action"));

        assertThrows(V2DomainException.class, () -> NavigationCommandParsers.parseOrbit("ORBIT"));
        assertThrows(V2DomainException.class, () -> NavigationCommandParsers.parseOrbit("ORBIT X"));
        V2DomainException radius = assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseOrbit("ORBIT L 25"));
        assertEquals("INVALID_INSTRUCTION", radius.code());
    }

    @Test
    void givenHoldWhenParsedThenAllThreeForms() {
        Map<String, Object> fixLeg = NavigationCommandParsers.parseHold("HOLD LMN R 090 60");
        assertEquals("FIX_AND_LEG", fixLeg.get("action"));
        assertEquals("LMN", fixLeg.get("fixPoint"));
        assertEquals("R", fixLeg.get("turnDirection"));
        assertEquals(90, fixLeg.get("inboundMagneticHeadingDeg"));
        assertEquals(60, fixLeg.get("legSeconds"));

        Map<String, Object> minutes = NavigationCommandParsers.parseHold("HOLD LMN L 270 2MIN");
        assertEquals(120, minutes.get("legSeconds"), "2MIN = 120 秒在 30–180 范围内");

        Map<String, Object> distance = NavigationCommandParsers.parseHold("HOLD LMN R 090 10NM");
        assertEquals(10.0, distance.get("legNm"));

        Map<String, Object> published = NavigationCommandParsers.parseHold("HOLD LMN01A");
        assertEquals("PUBLISHED_PROCEDURE", published.get("action"));

        Map<String, Object> exit = NavigationCommandParsers.parseHold("HOLD EXIT");
        assertEquals("EXIT", exit.get("action"));

        // 时长边界 30–180、距离 2–20
        assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseHold("HOLD LMN R 090 20"));
        assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseHold("HOLD LMN R 090 200"));
        assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseHold("HOLD LMN R 090 1NM"));
    }

    @Test
    void givenOffsetWhenParsedThenApplyOrClear() {
        Map<String, Object> apply = NavigationCommandParsers.parseOffset("OFFSET L 5NM");
        assertEquals("APPLY", apply.get("action"));
        assertEquals("L", apply.get("side"));
        assertEquals(5.0, apply.get("distanceNm"));

        Map<String, Object> clear = NavigationCommandParsers.parseOffset("OFFSET CLR");
        assertEquals("CLEAR", clear.get("action"));

        // 1–10NM 范围
        V2DomainException tooFar = assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseOffset("OFFSET R 12NM"));
        assertEquals("INVALID_INSTRUCTION", tooFar.code());
        assertThrows(V2DomainException.class, () -> NavigationCommandParsers.parseOffset("OFFSET R 0NM"));
    }

    @Test
    void givenVorWhenParsedThenStationDirectionRadialAndDme() {
        List<String> stations = Arrays.asList("VLM", "VP47");
        Map<String, Object> vor = NavigationCommandParsers.parseVor("VOR VLM IN 090", stations);
        assertEquals("VLM", vor.get("station"));
        assertEquals("IN", vor.get("direction"));
        assertEquals(90, vor.get("radialDeg"));

        Map<String, Object> dme = NavigationCommandParsers.parseVor(
                "VOR VP47 OUT 270 DME 15", stations);
        assertEquals(15.0, dme.get("dmeDistanceNm"));

        // 台站必须是 VOR/VOR_DME；普通航路点 422
        V2DomainException notVor = assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseVor("VOR P47 IN 090", stations));
        assertEquals("REFERENCE_NOT_FOUND", notVor.code());
        assertEquals(422, notVor.httpStatus());

        assertThrows(V2DomainException.class,
                () -> NavigationCommandParsers.parseVor("VOR VLM SIDEWAYS 090", stations));
    }
}
