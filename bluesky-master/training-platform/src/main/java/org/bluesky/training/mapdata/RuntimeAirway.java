package org.bluesky.training.mapdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuntimeAirway {
    private final String id;
    private final String code;
    private final String direction;
    private final List<String> pointCodes;
    private final List<String> segmentDirections;

    public RuntimeAirway(String id, String code, String direction,
                         List<String> pointCodes, List<String> segmentDirections) {
        this.id = id;
        this.code = code;
        this.direction = direction;
        this.pointCodes = Collections.unmodifiableList(new ArrayList<>(pointCodes));
        this.segmentDirections = Collections.unmodifiableList(new ArrayList<>(segmentDirections));
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getDirection() { return direction; }
    public List<String> getPointCodes() { return pointCodes; }
    public List<String> getSegmentDirections() { return segmentDirections; }
}
