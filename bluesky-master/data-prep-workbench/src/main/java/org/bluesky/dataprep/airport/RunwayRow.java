package org.bluesky.dataprep.airport;

public class RunwayRow {

    private String id;
    private String airportId;
    private String designation;
    private Double thr1Longitude;
    private Double thr1Latitude;
    private Double thr2Longitude;
    private Double thr2Latitude;
    private Integer lengthM;
    private Integer widthM;
    private Double trueHeadingDeg;
    private Double magneticHeadingDeg;
    private String surface;
    private String runwayStatus = "ACTIVE";
    private int orderNo;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAirportId() {
        return airportId;
    }

    public void setAirportId(String airportId) {
        this.airportId = airportId;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public Double getThr1Longitude() {
        return thr1Longitude;
    }

    public void setThr1Longitude(Double thr1Longitude) {
        this.thr1Longitude = thr1Longitude;
    }

    public Double getThr1Latitude() {
        return thr1Latitude;
    }

    public void setThr1Latitude(Double thr1Latitude) {
        this.thr1Latitude = thr1Latitude;
    }

    public Double getThr2Longitude() {
        return thr2Longitude;
    }

    public void setThr2Longitude(Double thr2Longitude) {
        this.thr2Longitude = thr2Longitude;
    }

    public Double getThr2Latitude() {
        return thr2Latitude;
    }

    public void setThr2Latitude(Double thr2Latitude) {
        this.thr2Latitude = thr2Latitude;
    }

    public Integer getLengthM() {
        return lengthM;
    }

    public void setLengthM(Integer lengthM) {
        this.lengthM = lengthM;
    }

    public Integer getWidthM() {
        return widthM;
    }

    public void setWidthM(Integer widthM) {
        this.widthM = widthM;
    }

    public Double getTrueHeadingDeg() {
        return trueHeadingDeg;
    }

    public void setTrueHeadingDeg(Double trueHeadingDeg) {
        this.trueHeadingDeg = trueHeadingDeg;
    }

    public Double getMagneticHeadingDeg() {
        return magneticHeadingDeg;
    }

    public void setMagneticHeadingDeg(Double magneticHeadingDeg) {
        this.magneticHeadingDeg = magneticHeadingDeg;
    }

    public String getSurface() {
        return surface;
    }

    public void setSurface(String surface) {
        this.surface = surface;
    }

    public String getRunwayStatus() {
        return runwayStatus;
    }

    public void setRunwayStatus(String runwayStatus) {
        this.runwayStatus = runwayStatus;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(int orderNo) {
        this.orderNo = orderNo;
    }
}
