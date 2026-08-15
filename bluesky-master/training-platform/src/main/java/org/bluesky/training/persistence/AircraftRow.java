package org.bluesky.training.persistence;

public class AircraftRow {
    private String id;
    private String assignedTerminalId;
    private String callsign;
    private String aircraftType;
    private String wakeCategory;
    private String transponderCode;
    private String origin;
    private String destination;
    private int appearanceOffsetMinutes;
    private Double latitude;
    private Double longitude;
    private String initialWaypoint;
    private double headingDegrees;
    private double altitudeFeet;
    private double speedKnots;
    private double verticalSpeedFeetPerMinute;
    private String routeText;
    private String activeInstructionText;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAssignedTerminalId() { return assignedTerminalId; }
    public void setAssignedTerminalId(String assignedTerminalId) { this.assignedTerminalId = assignedTerminalId; }
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
    public int getAppearanceOffsetMinutes() { return appearanceOffsetMinutes; }
    public void setAppearanceOffsetMinutes(int appearanceOffsetMinutes) { this.appearanceOffsetMinutes = appearanceOffsetMinutes; }
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
    public double getVerticalSpeedFeetPerMinute() { return verticalSpeedFeetPerMinute; }
    public void setVerticalSpeedFeetPerMinute(double verticalSpeedFeetPerMinute) { this.verticalSpeedFeetPerMinute = verticalSpeedFeetPerMinute; }
    public String getRouteText() { return routeText; }
    public void setRouteText(String routeText) { this.routeText = routeText; }
    public String getActiveInstructionText() { return activeInstructionText; }
    public void setActiveInstructionText(String activeInstructionText) { this.activeInstructionText = activeInstructionText; }
}
