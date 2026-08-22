package org.bluesky.dataprep.asf;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AircraftPerformanceAsfParserTest {
    @Test
    void parsesOneGroupAndUsesActualLayerCount() throws Exception {
        String content = "/0/\nA320/M/M B738/M/M\n*\n"
                + "N0200|N0250|N0300|\nN0150|30|50FT|1.2NM|\nN0140|\n20|\n"
                + "N0500|F450|45|Y|Y|\n24|\n"
                + "50|120|250|\n50|150|200|\n50|125|150|\n66|120|150|\n66|150|250|\n"
                + "1|\nF20|F50|\n"
                + "500|400|\n700|600|\n60|45|\n80|50|\nN0230|N0250|\n"
                + "N0170|N0190|\nN0230|N0250|\nN0200|N0220|\nH\n";
        AircraftPerformanceAsfParser.Result result = new AircraftPerformanceAsfParser().parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, result.groups.size());
        assertEquals(2, result.groups.get(0).aircraft.size());
        assertEquals(2, result.groups.get(0).altitudeLayers.size());
        assertEquals("N0250", result.groups.get(0).curves.get(4).get(1));
        assertTrue(result.warnings.get(0).contains("按实际 2 层"));
    }
}
