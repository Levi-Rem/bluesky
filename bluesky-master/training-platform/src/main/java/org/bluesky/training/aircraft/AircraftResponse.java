package org.bluesky.training.aircraft;

import org.bluesky.training.persistence.AircraftRow;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class AircraftResponse {
    private final String id;
    private final String assignedTerminalId;
    private final String callsign;
    private final String aircraftType;
    private final String wakeCategory;
    private final String transponderCode;
    private final String origin;
    private final String destination;
    private final int appearanceOffsetMinutes;
    private final Double latitude;
    private final Double longitude;
    private final double headingDegrees;
    private final double altitudeFeet;
    private final double speedKnots;
    private final double verticalSpeedFeetPerMinute;
    private final List<String> route;
    private final String activeInstruction;

    public AircraftResponse(AircraftRow row) {
        this.id = row.getId();
        this.assignedTerminalId = row.getAssignedTerminalId();
        this.callsign = row.getCallsign();
        this.aircraftType = row.getAircraftType();
        this.wakeCategory = row.getWakeCategory();
        this.transponderCode = row.getTransponderCode();
        this.origin = row.getOrigin();
        this.destination = row.getDestination();
        this.appearanceOffsetMinutes = row.getAppearanceOffsetMinutes();
        this.latitude = row.getLatitude();
        this.longitude = row.getLongitude();
        this.headingDegrees = row.getHeadingDegrees();
        this.altitudeFeet = row.getAltitudeFeet();
        this.speedKnots = row.getSpeedKnots();
        this.verticalSpeedFeetPerMinute = row.getVerticalSpeedFeetPerMinute();
        this.route = splitRoute(row.getRouteText());
        this.activeInstruction = row.getActiveInstructionText();
    }

    private static List<String> splitRoute(String routeText) {
        if (routeText == null || routeText.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(routeText.split(" ")).filter(value -> !value.isEmpty()).collect(Collectors.toList());
    }

    public String getId() { return id; }
    public String getAssignedTerminalId() { return assignedTerminalId; }
    public String getCallsign() { return callsign; }
    public String getAircraftType() { return aircraftType; }
    public String getWakeCategory() { return wakeCategory; }
    public String getTransponderCode() { return transponderCode; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public int getAppearanceOffsetMinutes() { return appearanceOffsetMinutes; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public double getHeadingDegrees() { return headingDegrees; }
    public double getAltitudeFeet() { return altitudeFeet; }
    public double getSpeedKnots() { return speedKnots; }
    public double getVerticalSpeedFeetPerMinute() { return verticalSpeedFeetPerMinute; }
    public List<String> getRoute() { return route; }
    public String getActiveInstruction() { return activeInstruction; }
}
