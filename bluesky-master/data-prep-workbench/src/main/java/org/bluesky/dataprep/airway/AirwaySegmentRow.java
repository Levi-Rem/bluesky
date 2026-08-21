package org.bluesky.dataprep.airway;

public class AirwaySegmentRow {

    private String id;
    private String airwayId;
    private int orderNo;
    private String startPointId;
    private String endPointId;
    private String segmentDirection;
    private Double lowerValue;
    private String lowerReference;
    private Double upperValue;
    private String upperReference;
    /** 展示用（JOIN 解析），提交时可省略。 */
    private String startPointCode;
    private String endPointCode;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAirwayId() {
        return airwayId;
    }

    public void setAirwayId(String airwayId) {
        this.airwayId = airwayId;
    }

    public int getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(int orderNo) {
        this.orderNo = orderNo;
    }

    public String getStartPointId() {
        return startPointId;
    }

    public void setStartPointId(String startPointId) {
        this.startPointId = startPointId;
    }

    public String getEndPointId() {
        return endPointId;
    }

    public void setEndPointId(String endPointId) {
        this.endPointId = endPointId;
    }

    public String getSegmentDirection() {
        return segmentDirection;
    }

    public void setSegmentDirection(String segmentDirection) {
        this.segmentDirection = segmentDirection;
    }

    public Double getLowerValue() {
        return lowerValue;
    }

    public void setLowerValue(Double lowerValue) {
        this.lowerValue = lowerValue;
    }

    public String getLowerReference() {
        return lowerReference;
    }

    public void setLowerReference(String lowerReference) {
        this.lowerReference = lowerReference;
    }

    public Double getUpperValue() {
        return upperValue;
    }

    public void setUpperValue(Double upperValue) {
        this.upperValue = upperValue;
    }

    public String getUpperReference() {
        return upperReference;
    }

    public void setUpperReference(String upperReference) {
        this.upperReference = upperReference;
    }

    public String getStartPointCode() {
        return startPointCode;
    }

    public void setStartPointCode(String startPointCode) {
        this.startPointCode = startPointCode;
    }

    public String getEndPointCode() {
        return endPointCode;
    }

    public void setEndPointCode(String endPointCode) {
        this.endPointCode = endPointCode;
    }
}
