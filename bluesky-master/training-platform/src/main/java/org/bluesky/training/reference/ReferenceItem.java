package org.bluesky.training.reference;

public final class ReferenceItem {
    private final String code;
    private final String name;
    private final Double latitude;
    private final Double longitude;

    public ReferenceItem(String code, String name, Double latitude, Double longitude) {
        this.code = code;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
}
