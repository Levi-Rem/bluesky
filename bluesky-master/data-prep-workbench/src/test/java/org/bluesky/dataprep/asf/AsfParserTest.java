package org.bluesky.dataprep.asf;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsfParserTest {

    private final AsfParser parser = new AsfParser();

    @Test
    void parsesDmsCoordinatesAndKeepsFirstDuplicateDefinition() {
        String text = "/DEFINITIONS/\n"
                + "ZBAA | 400430N1163524E | AIRPORT_I | Y | ZBBB | N | N | | BEIJING A/P\n"
                + "ZBAA | 400431N1163524E | AIRPORT_I | Y | ZBBB | N | N | | conflicting\n"
                + "PUD | 311000N1214800E | VORDME | Y | | N | N | |\n";

        AsfParser.PointResult result = parser.parsePoints(stream(text));

        assertThat(result.getPoints()).hasSize(2);
        assertThat(result.getConflicts()).hasSize(1).first().asString().contains("保留第 2 行");
        assertThat(result.getPoints().get("ZBAA").latitude).isEqualTo(40.075d);
        assertThat(result.getPoints().get("ZBAA").longitude).isEqualTo(116.59d);
        assertThat(result.getPoints().get("ZBAA").applicableAirports).isEqualTo("ZBBB");
    }

    @Test
    void parsesV5RouteAndContinuationLines() {
        String text = "/CODED_ROUTE/\n"
                + "FPL_PBN_MISMATCH_DISPLAY | 5 |\n"
                + "A1 | B | NONE | RNAV2 | RNP1 | N | PUD SASAN\n"
                + "   |   |      |       |      |   | AND ZBAA\n"
                + "/CODED_ROUTE_SEGMENTS/\n"
                + "/SID/\n"
                + "BOVMA901L | ZGGG | LMH | 01L | D1 | O1 | PUD SASAN AND\n"
                + "ELIGIBLE_ROUTE | BOVMA\n"
                + "/STAR/\n"
                + "ENVIP901L | ZGGG | LMH | 01L | D1 | O1 | ZBAA AND SASAN\n"
                + "ELIGIBLE_ROUTE | ENVIP\n"
                + "/SPR/\n";

        List<AsfParser.Route> routes = parser.parseRoutes(stream(text));

        assertThat(routes).hasSize(3);
        assertThat(routes.get(0).code).isEqualTo("A1");
        assertThat(routes.get(0).pointCodes).containsExactly("PUD", "SASAN", "AND", "ZBAA");
        assertThat(routes.get(0).rnavCapability).isEqualTo("NONE");
        assertThat(routes.get(0).rnavCapabilityPost2012).isEqualTo("RNAV2");
        assertThat(routes.get(0).rnpCapabilityPost2012).isEqualTo("RNP1");
        assertThat(routes.get(0).rvsmLevel).isEqualTo("N");
        assertThat(routes.get(0).routeType).isEqualTo("CODED_ROUTE");
        assertThat(routes.get(1).routeType).isEqualTo("SID");
        assertThat(routes.get(1).procedureAirport).isEqualTo("ZGGG");
        assertThat(routes.get(1).procedureRunway).isEqualTo("01L");
        assertThat(routes.get(1).eligibleRoute).isEqualTo("BOVMA");
        assertThat(routes.get(2).routeType).isEqualTo("STAR");
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
