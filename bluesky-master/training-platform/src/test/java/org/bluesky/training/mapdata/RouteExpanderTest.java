package org.bluesky.training.mapdata;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteExpanderTest {

    private final RuntimeNavigationPoint pud = point("PUD", 121.8, 31.1);
    private final RuntimeNavigationPoint pikop = point("PIKOP", 121.3, 31.0);
    private final RuntimeNavigationPoint akoma = point("AKOMA", 120.8, 30.8);
    private final RuntimeNavigationPoint bemag = point("BEMAG", 120.1, 30.5);
    private final RouteExpander expander = new RouteExpander(
            Arrays.asList(pud, pikop, akoma, bemag),
            Collections.singletonList(new RuntimeAirway("airway-a593", "A593", "BOTH",
                    Arrays.asList("PUD", "PIKOP", "AKOMA"), Arrays.asList("BOTH", "FORWARD"))));

    @Test
    void expandsDirectAndAirwaySegmentsAndOnlyRemovesAdjacentDuplicates() {
        assertThat(expander.expand(Arrays.asList("PUD", "A593", "AKOMA", "DCT", "BEMAG")))
                .extracting(RuntimeNavigationPoint::getCode)
                .containsExactly("PUD", "PIKOP", "AKOMA", "BEMAG");
        assertThat(expander.expand(Arrays.asList("PUD", "AKOMA", "PUD")))
                .extracting(RuntimeNavigationPoint::getCode)
                .containsExactly("PUD", "AKOMA", "PUD");
    }

    @Test
    void honorsSegmentDirectionAndRejectsUnknownOrUnsupportedTokens() {
        assertThatThrownBy(() -> expander.expand(Arrays.asList("AKOMA", "A593", "PUD")))
                .isInstanceOf(ReferenceDataException.class)
                .extracting("code").isEqualTo("AIRWAY_DIRECTION_NOT_ALLOWED");
        assertThatThrownBy(() -> expander.expand(Arrays.asList("PUD", "UNKNOWN")))
                .isInstanceOf(ReferenceDataException.class)
                .extracting("code").isEqualTo("UNKNOWN_NAVIGATION_POINT");
        assertThatThrownBy(() -> expander.expand(Arrays.asList("PUD", "SID-ZSPD")))
                .isInstanceOf(ReferenceDataException.class)
                .extracting("code").isEqualTo("UNSUPPORTED_ROUTE_TOKEN");
    }

    private RuntimeNavigationPoint point(String code, double longitude, double latitude) {
        return new RuntimeNavigationPoint("point-" + code, code, code, "WAYPOINT",
                latitude, longitude, null);
    }
}
