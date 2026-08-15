package org.bluesky.training.aircraft;

import java.util.List;

public class CreateAircraftRequest {
    private String callsign;
    private String aircraftType;
    private String wakeCategory;
    private String transponderCode;
    private String origin;
    private String destination;
    private String appearanceOffsetMinutes;
    private Double latitude;
    private Double longitude;
    private String initialWaypoint;
    private double headingDegrees;
    private double altitudeFeet;
    private double speedKnots;
    private List<String> route;

    public String getCallsign() { return callsign; }
    public void setCallsign(String callsign) { this.callsign = callsign; }
    public String getAircraftType() { return aircraftType; }
    public void setAircraftType(String aircraftType) { this.aircraftType = aircraftType; }
    public String getWakeCategory() { return wakeCategory; }
    public void setWakeCategory(String wakeCategory) { this.wakeCategory = wakeCategory; }
    public String getTransponderCode() { return transponderCode; }
    public void setTransponderCode(String transponderCode) { this.transponderCode = transponderCode; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getAppearanceOffsetMinutes() { return appearanceOffsetMinutes; }
    public void setAppearanceOffsetMinutes(String appearanceOffsetMinutes) { this.appearanceOffsetMinutes = appearanceOffsetMinutes; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getInitialWaypoint() { return initialWaypoint; }
    public void setInitialWaypoint(String initialWaypoint) { this.initialWaypoint = initialWaypoint; }
    public double getHeadingDegrees() { return headingDegrees; }
    public void setHeadingDegrees(double headingDegrees) { this.headingDegrees = headingDegrees; }
    public double getAltitudeFeet() { return altitudeFeet; }
    public void setAltitudeFeet(double altitudeFeet) { this.altitudeFeet = altitudeFeet; }
    public double getSpeedKnots() { return speedKnots; }
    public void setSpeedKnots(double speedKnots) { this.speedKnots = speedKnots; }
    public List<String> getRoute() { return route; }
    public void setRoute(List<String> route) { this.route = route; }
}
