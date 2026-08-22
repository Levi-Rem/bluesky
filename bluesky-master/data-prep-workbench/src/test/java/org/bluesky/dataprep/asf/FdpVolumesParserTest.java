package org.bluesky.dataprep.asf;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FdpVolumesParserTest {
    @Test
    void parsesSectorAndFirDefinitions() {
        String content = "/POINTS/\n"
                + "P1 | 220000N1130000E |\nP2 | 221000N1130000E |\nP3 | 221000N1140000E |\n"
                + "/LAYER/\n1 | S0180 |\n2 | S0210 |\n"
                + "/VOLUME/\nV01 | 1 | P1 P2 P3 P1\nV02 | 2 | P1 P2 P3 P1\n"
                + "/SECTOR/\nS1 | HI | Y | V01 + V02\n"
                + "/FIR/\nF1 | V01 + V02\n";
        FdpVolumesParser.Result result = new FdpVolumesParser().parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, result.points.size());
        assertEquals(2, result.volumes.size());
        assertEquals(1, result.sectors.size());
        assertEquals("S1", result.sectors.get(0).name);
        assertEquals(1, result.firs.size());
        assertEquals(3, result.volumes.get("V01").pointNames.size());
    }
}
