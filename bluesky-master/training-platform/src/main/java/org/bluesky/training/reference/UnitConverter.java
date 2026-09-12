package org.bluesky.training.reference;

/** P03：唯一单位转换入口（详细设计 4.3）。 */
public final class UnitConverter {

    public static final double FEET_TO_METERS = 0.3048;
    public static final double KNOT_TO_METERS_PER_SECOND = 1852.0 / 3600.0;

    private UnitConverter() {
    }

    public static double feetToMeters(double feet) {
        return feet * FEET_TO_METERS;
    }

    public static double metersToFeet(double meters) {
        return meters / FEET_TO_METERS;
    }

    public static double knotsToMetersPerSecond(double knots) {
        return knots * KNOT_TO_METERS_PER_SECOND;
    }

    public static double fpmToMetersPerSecond(double fpm) {
        return feetToMeters(fpm) / 60.0;
    }

    public static double flightLevelToFeet(double flightLevel) {
        if (flightLevel < 0) {
            throw new IllegalArgumentException("飞行高度层不能为负: " + flightLevel);
        }
        return flightLevel * 100.0;
    }
}
