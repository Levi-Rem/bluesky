package org.bluesky.training.instruction;

import org.bluesky.training.mapdata.RuntimeNavigationPoint;

import java.util.ArrayList;
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
    private final RuntimeNavigationPoint waypointPoint;
    private final List<RuntimeNavigationPoint> routePoints;
    private final String parametersJson;

    public EngineInstructionCommand() {
        this(null, null, null, null, null, null, null, null, null,
                Collections.emptyList(), null, Collections.emptyList());
    }

    public EngineInstructionCommand(String callsign, String type, Double headingDegrees,
                                    Double altitudeFeet, Double verticalSpeedFeetPerMinute,
                                    Double speedKnots, Double mach, String waypoint, List<String> route) {
        this(callsign, null, type, headingDegrees, altitudeFeet, verticalSpeedFeetPerMinute,
                speedKnots, mach, waypoint, route, null, Collections.emptyList());
    }

    private EngineInstructionCommand(String callsign, String commandId, String type,
                                     Double headingDegrees, Double altitudeFeet,
                                     Double verticalSpeedFeetPerMinute, Double speedKnots,
                                     Double mach, String waypoint, List<String> route,
                                     RuntimeNavigationPoint waypointPoint,
                                     List<RuntimeNavigationPoint> routePoints) {
        this(callsign, commandId, type, headingDegrees, altitudeFeet, verticalSpeedFeetPerMinute,
                speedKnots, mach, waypoint, route, waypointPoint, routePoints, null);
    }

    private EngineInstructionCommand(String callsign, String commandId, String type,
                                     Double headingDegrees, Double altitudeFeet,
                                     Double verticalSpeedFeetPerMinute, Double speedKnots,
                                     Double mach, String waypoint, List<String> route,
                                     RuntimeNavigationPoint waypointPoint,
                                     List<RuntimeNavigationPoint> routePoints,
                                     String parametersJson) {
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
        this.waypointPoint = waypointPoint;
        this.routePoints = routePoints == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routePoints));
        this.parametersJson = parametersJson;
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
    public RuntimeNavigationPoint getWaypointPoint() { return waypointPoint; }
    public List<RuntimeNavigationPoint> getRoutePoints() { return routePoints; }
    public String getParametersJson() { return parametersJson; }

    /** 迁移桥透传完整 v2 解析参数（复杂引导类型按参数字典执行）。 */
    public EngineInstructionCommand withParametersJson(String value) {
        return new EngineInstructionCommand(callsign, commandId, type, headingDegrees,
                altitudeFeet, verticalSpeedFeetPerMinute, speedKnots, mach, waypoint, route,
                waypointPoint, routePoints, value);
    }

    public EngineInstructionCommand withCommandId(String value) {
        return new EngineInstructionCommand(callsign, value, type, headingDegrees,
                altitudeFeet, verticalSpeedFeetPerMinute, speedKnots, mach, waypoint, route,
                waypointPoint, routePoints);
    }

    public EngineInstructionCommand withVerticalSpeedFeetPerMinute(Double value) {
        return new EngineInstructionCommand(callsign, commandId, type, headingDegrees,
                altitudeFeet, value, speedKnots, mach, waypoint, route,
                waypointPoint, routePoints);
    }

    public EngineInstructionCommand withResolvedPoints(RuntimeNavigationPoint resolvedWaypoint,
                                                       List<RuntimeNavigationPoint> resolvedRoute) {
        return new EngineInstructionCommand(callsign, commandId, type, headingDegrees,
                altitudeFeet, verticalSpeedFeetPerMinute, speedKnots, mach, waypoint, route,
                resolvedWaypoint, resolvedRoute);
    }
}
