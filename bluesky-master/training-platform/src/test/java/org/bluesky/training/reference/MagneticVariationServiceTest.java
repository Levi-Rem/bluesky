package org.bluesky.training.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P03：快照固定磁差模型（详细设计 4.3：转换失败时拒绝相关命令）。 */
class MagneticVariationServiceTest {

    private static final String MODEL_JSON = "{"
            + "\"modelName\":\"TEST-MM\",\"epoch\":2025.0,"
            + "\"region\":{\"minLat\":20.0,\"maxLat\":24.0,\"minLon\":110.0,\"maxLon\":116.0},"
            + "\"gridPoints\":["
            + "{\"lat\":20.0,\"lon\":110.0,\"variationDeg\":-2.0},"
            + "{\"lat\":20.0,\"lon\":116.0,\"variationDeg\":-1.0},"
            + "{\"lat\":24.0,\"lon\":110.0,\"variationDeg\":1.0},"
            + "{\"lat\":24.0,\"lon\":116.0,\"variationDeg\":2.0}"
            + "]}";

    private final MagneticVariationService service =
            MagneticVariationService.load(MODEL_JSON);

    @Test
    void givenGridCornersWhenInterpolatingThenValuesMatch() {
        assertEquals(-2.0, service.variationDeg(20.0, 110.0), 1e-9);
        assertEquals(2.0, service.variationDeg(24.0, 116.0), 1e-9);
        // 中心点双线性插值应为四角平均 = 0
        assertEquals(0.0, service.variationDeg(22.0, 113.0), 1e-9);
    }

    @Test
    void givenTrueAndMagneticWhenRoundTrippingThenHeadingIsStable() {
        double variation = service.variationDeg(23.0, 113.5);
        double trueHeading = service.magneticToTrue(90.0, 23.0, 113.5);
        assertEquals(90.0 + variation, trueHeading, 1e-9);
        assertEquals(90.0, service.trueToMagnetic(trueHeading, 23.0, 113.5), 1e-9);
    }

    @Test
    void givenHeadingOutsideCompassRangeWhenNormalizedThenWraps() {
        assertEquals(359.5, service.magneticToTrue(-0.5 - service.variationDeg(22.0, 112.0), 22.0, 112.0), 1e-9);
    }

    @Test
    void givenPointOutsideRegionWhenConvertingThenRejected() {
        org.bluesky.training.common.V2DomainException failure = assertThrows(
                org.bluesky.training.common.V2DomainException.class,
                () -> service.variationDeg(30.0, 113.0));
        assertEquals(422, failure.httpStatus());
        assertEquals("REFERENCE_NOT_FOUND", failure.code());

        assertThrows(org.bluesky.training.common.V2DomainException.class,
                () -> service.variationDeg(22.0, 120.0));
    }

    @Test
    void givenConstantModelWhenLoadedThenUsedInsideRegion() {
        MagneticVariationService constant = MagneticVariationService.load("{"
                + "\"modelName\":\"CONST\",\"epoch\":2025.0,"
                + "\"region\":{\"minLat\":-90.0,\"maxLat\":90.0,\"minLon\":-180.0,\"maxLon\":180.0},"
                + "\"constantDeg\":-7.5}");
        assertEquals(-7.5, constant.variationDeg(0.0, 0.0), 1e-9);
        // 磁航向 90 + 磁差(-7.5) = 真航向 82.5
        assertEquals(82.5, constant.magneticToTrue(90.0, 10.0, 10.0), 1e-9);
        assertEquals(90.0, constant.trueToMagnetic(82.5, 10.0, 10.0), 1e-9);
    }
}
