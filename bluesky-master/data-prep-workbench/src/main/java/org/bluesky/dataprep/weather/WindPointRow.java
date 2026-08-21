package org.bluesky.dataprep.weather;

public class WindPointRow {

    private String id;
    private String windFieldId;
    private int orderNo;
    private Double longitude;
    private Double latitude;
    private Integer altitudeM;
    private Double windDirectionDeg;
    private Double windSpeedMs;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWindFieldId() {
        return windFieldId;
    }

    public void setWindFieldId(String windFieldId) {
        this.windFieldId = windFieldId;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(int orderNo) {
        this.orderNo = orderNo;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Integer getAltitudeM() {
        return altitudeM;
    }

    public void setAltitudeM(Integer altitudeM) {
        this.altitudeM = altitudeM;
    }

    public Double getWindDirectionDeg() {
        return windDirectionDeg;
    }

    public void setWindDirectionDeg(Double windDirectionDeg) {
        this.windDirectionDeg = windDirectionDeg;
    }

    public Double getWindSpeedMs() {
        return windSpeedMs;
    }

    public void setWindSpeedMs(Double windSpeedMs) {
        this.windSpeedMs = windSpeedMs;
    }
}
