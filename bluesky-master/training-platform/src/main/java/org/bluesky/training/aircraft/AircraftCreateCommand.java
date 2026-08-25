package org.bluesky.training.aircraft;

import org.bluesky.training.mapdata.RuntimeNavigationPoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AircraftCreateCommand {
    private final String callsign;
    private final String aircraftType;
    private final String wakeCategory;
    private final String transponderCode;
    private final String origin;
    private final String destination;
    private final int appearanceOffsetMinutes;
    private final Double latitude;
    private final Double longitude;
    private final String initialWaypoint;
    private final double headingDegrees;
    private final double altitudeFeet;
    private final double speedKnots;
    private final List<String> route;
    private final RuntimeNavigationPoint originPoint;
    private final RuntimeNavigationPoint destinationPoint;
    private final RuntimeNavigationPoint initialPoint;
    private final List<RuntimeNavigationPoint> routePoints;

    public AircraftCreateCommand(String callsign, String aircraftType, String wakeCategory,
                                 String transponderCode, String origin, String destination,
                                 int appearanceOffsetMinutes, Double latitude, Double longitude,
                                 String initialWaypoint, double headingDegrees,
                                 double altitudeFeet, double speedKnots, List<String> route) {
        this.callsign = callsign;
        this.aircraftType = aircraftType;
        this.wakeCategory = wakeCategory;
        this.transponderCode = transponderCode;
        this.origin = origin;
        this.destination = destination;
        this.appearanceOffsetMinutes = appearanceOffsetMinutes;
        this.latitude = latitude;
        this.longitude = longitude;
        this.initialWaypoint = initialWaypoint;
        this.headingDegrees = headingDegrees;
        this.altitudeFeet = altitudeFeet;
        this.speedKnots = speedKnots;
        this.route = route == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(route));
        this.originPoint = null;
        this.destinationPoint = null;
        this.initialPoint = null;
        this.routePoints = Collections.emptyList();
    }

    private AircraftCreateCommand(AircraftCreateCommand source,
                                  RuntimeNavigationPoint originPoint,
                                  RuntimeNavigationPoint destinationPoint,
                                  RuntimeNavigationPoint initialPoint,
                                  List<RuntimeNavigationPoint> routePoints) {
        this.callsign = source.callsign;
        this.aircraftType = source.aircraftType;
        this.wakeCategory = source.wakeCategory;
        this.transponderCode = source.transponderCode;
        this.origin = source.origin;
        this.destination = source.destination;
        this.appearanceOffsetMinutes = source.appearanceOffsetMinutes;
        this.latitude = source.latitude;
        this.longitude = source.longitude;
        this.initialWaypoint = source.initialWaypoint;
        this.headingDegrees = source.headingDegrees;
        this.altitudeFeet = source.altitudeFeet;
        this.speedKnots = source.speedKnots;
        this.route = source.route;
        this.originPoint = originPoint;
        this.destinationPoint = destinationPoint;
        this.initialPoint = initialPoint;
        this.routePoints = Collections.unmodifiableList(new ArrayList<>(routePoints));
    }

    public String getCallsign() { return callsign; }
    public String getAircraftType() { return aircraftType; }
    public String getWakeCategory() { return wakeCategory; }
    public String getTransponderCode() { return transponderCode; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public int getAppearanceOffsetMinutes() { return appearanceOffsetMinutes; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getInitialWaypoint() { return initialWaypoint; }
    public double getHeadingDegrees() { return headingDegrees; }
    public double getAltitudeFeet() { return altitudeFeet; }
    public double getSpeedKnots() { return speedKnots; }
    public List<String> getRoute() { return route; }
    public RuntimeNavigationPoint getOriginPoint() { return originPoint; }
    public RuntimeNavigationPoint getDestinationPoint() { return destinationPoint; }
    public RuntimeNavigationPoint getInitialPoint() { return initialPoint; }
    public List<RuntimeNavigationPoint> getRoutePoints() { return routePoints; }

    public AircraftCreateCommand withResolvedPoints(RuntimeNavigationPoint resolvedOrigin,
                                                    RuntimeNavigationPoint resolvedDestination,
                                                    RuntimeNavigationPoint resolvedInitial,
                                                    List<RuntimeNavigationPoint> resolvedRoute) {
        return new AircraftCreateCommand(this, resolvedOrigin, resolvedDestination,
                resolvedInitial, resolvedRoute);
    }
}
