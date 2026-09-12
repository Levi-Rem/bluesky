package org.bluesky.training.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.common.V2DomainException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * P03：使用快照固定磁差模型（详细设计 4.3：模型名称、历元、适用区域随快照保存；
 * 区域外转换失败时拒绝相关命令）。
 */
public final class MagneticVariationService {

    private final String modelName;
    private final double epoch;
    private final double minLat;
    private final double maxLat;
    private final double minLon;
    private final double maxLon;
    private final Double constantDeg;
    private final List<double[]> gridPoints; // {lat, lon, variationDeg}

    private MagneticVariationService(String modelName, double epoch,
                                     double minLat, double maxLat,
                                     double minLon, double maxLon,
                                     Double constantDeg, List<double[]> gridPoints) {
        this.modelName = modelName;
        this.epoch = epoch;
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLon = minLon;
        this.maxLon = maxLon;
        this.constantDeg = constantDeg;
        this.gridPoints = gridPoints;
    }

    public static MagneticVariationService load(String modelJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(modelJson);
            JsonNode region = root.path("region");
            List<double[]> points = new ArrayList<>();
            for (JsonNode point : root.path("gridPoints")) {
                points.add(new double[]{
                        point.path("lat").asDouble(),
                        point.path("lon").asDouble(),
                        point.path("variationDeg").asDouble()});
            }
            Double constant = root.hasNonNull("constantDeg")
                    ? root.path("constantDeg").asDouble() : null;
            return new MagneticVariationService(
                    root.path("modelName").asText("UNKNOWN"),
                    root.path("epoch").asDouble(0.0),
                    region.path("minLat").asDouble(-90.0),
                    region.path("maxLat").asDouble(90.0),
                    region.path("minLon").asDouble(-180.0),
                    region.path("maxLon").asDouble(180.0),
                    constant,
                    Collections.unmodifiableList(points));
        } catch (Exception e) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "磁差模型解析失败: " + e.getMessage());
        }
    }

    public String modelName() {
        return modelName;
    }

    public double epoch() {
        return epoch;
    }

    public double variationDeg(double latitudeDeg, double longitudeDeg) {
        requireInsideRegion(latitudeDeg, longitudeDeg);
        Double interpolated = interpolateGrid(latitudeDeg, longitudeDeg);
        if (interpolated != null) {
            return interpolated;
        }
        if (constantDeg != null) {
            return constantDeg;
        }
        throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                "磁差模型缺少网格或常量参数，无法计算该位置的磁差");
    }

    public double trueToMagnetic(double trueHeadingDeg, double latitudeDeg, double longitudeDeg) {
        return wrap360(trueHeadingDeg - variationDeg(latitudeDeg, longitudeDeg));
    }

    public double magneticToTrue(double magneticHeadingDeg, double latitudeDeg, double longitudeDeg) {
        return wrap360(magneticHeadingDeg + variationDeg(latitudeDeg, longitudeDeg));
    }

    private void requireInsideRegion(double latitudeDeg, double longitudeDeg) {
        if (latitudeDeg < minLat || latitudeDeg > maxLat
                || longitudeDeg < minLon || longitudeDeg > maxLon) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    String.format("位置 (%.4f, %.4f) 超出磁差模型 %s 适用区域", latitudeDeg, longitudeDeg, modelName),
                    Arrays.asList("latitudeDeg", "longitudeDeg"));
        }
    }

    private Double interpolateGrid(double latitudeDeg, double longitudeDeg) {
        if (gridPoints.isEmpty()) {
            return null;
        }
        TreeSet<Double> lats = new TreeSet<>();
        TreeSet<Double> lons = new TreeSet<>();
        for (double[] point : gridPoints) {
            lats.add(point[0]);
            lons.add(point[1]);
        }
        Double lat1 = floorOf(lats, latitudeDeg);
        Double lat2 = ceilingOf(lats, latitudeDeg);
        Double lon1 = floorOf(lons, longitudeDeg);
        Double lon2 = ceilingOf(lons, longitudeDeg);
        if (lat1 == null || lat2 == null || lon1 == null || lon2 == null) {
            return null;
        }
        double v11 = valueAt(lat1, lon1);
        double v21 = valueAt(lat2, lon1);
        double v12 = valueAt(lat1, lon2);
        double v22 = valueAt(lat2, lon2);
        if (Double.isNaN(v11) || Double.isNaN(v21) || Double.isNaN(v12) || Double.isNaN(v22)) {
            return null;
        }
        double latSpan = lat2 - lat1;
        double lonSpan = lon2 - lon1;
        double latRatio = latSpan == 0 ? 0 : (latitudeDeg - lat1) / latSpan;
        double lonRatio = lonSpan == 0 ? 0 : (longitudeDeg - lon1) / lonSpan;
        double top = v11 + (v12 - v11) * lonRatio;
        double bottom = v21 + (v22 - v21) * lonRatio;
        return top + (bottom - top) * latRatio;
    }

    private double valueAt(double lat, double lon) {
        for (double[] point : gridPoints) {
            if (point[0] == lat && point[1] == lon) {
                return point[2];
            }
        }
        return Double.NaN;
    }

    private static Double floorOf(TreeSet<Double> values, double target) {
        Double floor = values.floor(target);
        return floor == null ? values.first() : floor;
    }

    private static Double ceilingOf(TreeSet<Double> values, double target) {
        Double ceiling = values.ceiling(target);
        return ceiling == null ? values.last() : ceiling;
    }

    private static double wrap360(double headingDeg) {
        double wrapped = headingDeg % 360.0;
        if (wrapped < 0) {
            wrapped += 360.0;
        }
        return wrapped == 360.0 ? 0.0 : wrapped;
    }
}
