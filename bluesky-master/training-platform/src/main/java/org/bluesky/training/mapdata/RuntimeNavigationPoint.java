package org.bluesky.training.mapdata;

import com.fasterxml.jackson.annotation.JsonIgnore;

public final class RuntimeNavigationPoint {
    private final String id;
    private final String code;
    private final String name;
    private final String type;
    private final double latitude;
    private final double longitude;
    private final Integer elevationMeters;

    public RuntimeNavigationPoint(String id, String code, String name, String type,
                                  double latitude, double longitude, Integer elevationMeters) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.elevationMeters = elevationMeters;
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    @JsonIgnore
    public String getName() { return name; }
    public String getType() { return type; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Integer getElevationMeters() { return elevationMeters; }
}
