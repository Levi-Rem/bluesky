package org.bluesky.training.instruction;

import java.util.Collections;
import java.util.List;

public final class EngineInstructionCommand {
    private final String callsign;
    private final String commandId;
    private final String type;
    private final Double headingDegrees;
    private final Double altitudeFeet;
    private final Double verticalSpeedFeetPerMinute;
    private final Double speedKnots;
    private final Double mach;
    private final String waypoint;
    private final List<String> route;

    public EngineInstructionCommand() {
        this(null, null, null, null, null, null, null, null, null, Collections.emptyList());
    }

    public EngineInstructionCommand(String callsign, String type, Double headingDegrees,
                                    Double altitudeFeet, Double verticalSpeedFeetPerMinute,
                                    Double speedKnots, Double mach, String waypoint, List<String> route) {
        this(callsign, null, type, headingDegrees, altitudeFeet, verticalSpeedFeetPerMinute,
                speedKnots, mach, waypoint, route);
    }

    private EngineInstructionCommand(String callsign, String commandId, String type,
                                     Double headingDegrees, Double altitudeFeet,
                                     Double verticalSpeedFeetPerMinute, Double speedKnots,
                                     Double mach, String waypoint, List<String> route) {
        this.callsign = callsign;
        this.commandId = commandId;
        this.type = type;
        this.headingDegrees = headingDegrees;
        this.altitudeFeet = altitudeFeet;
        this.verticalSpeedFeetPerMinute = verticalSpeedFeetPerMinute;
        this.speedKnots = speedKnots;
        this.mach = mach;
        this.waypoint = waypoint;
        this.route = route == null ? Collections.emptyList() : route;
    }

    public String getCallsign() { return callsign; }
    public String getCommandId() { return commandId; }
    public String getType() { return type; }
    public Double getHeadingDegrees() { return headingDegrees; }
    public Double getAltitudeFeet() { return altitudeFeet; }
    public Double getVerticalSpeedFeetPerMinute() { return verticalSpeedFeetPerMinute; }
    public Double getSpeedKnots() { return speedKnots; }
    public Double getMach() { return mach; }
    public String getWaypoint() { return waypoint; }
    public List<String> getRoute() { return route; }

    public EngineInstructionCommand withCommandId(String value) {
        return new EngineInstructionCommand(callsign, value, type, headingDegrees,
                altitudeFeet, verticalSpeedFeetPerMinute, speedKnots, mach, waypoint, route);
    }

    public EngineInstructionCommand withVerticalSpeedFeetPerMinute(Double value) {
        return new EngineInstructionCommand(callsign, commandId, type, headingDegrees,
                altitudeFeet, value, speedKnots, mach, waypoint, route);
    }
}
