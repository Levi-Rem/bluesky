package org.bluesky.dataprep.physicalsector;

/** 物理扇区有序边界点；可引用空域信息点，也可直接保存经纬度。 */
public class PhysicalSectorPointRow {
    private String id;
    private String physicalSectorId;
    private int orderNo;
    private String navPointId;
    private String pointName;
    private String coordinateText;
    private Double longitude;
    private Double latitude;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPhysicalSectorId() { return physicalSectorId; }
    public void setPhysicalSectorId(String physicalSectorId) { this.physicalSectorId = physicalSectorId; }
    public int getOrderNo() { return orderNo; }
    public void setOrderNo(int orderNo) { this.orderNo = orderNo; }
    public String getNavPointId() { return navPointId; }
    public void setNavPointId(String navPointId) { this.navPointId = navPointId; }
    public String getPointName() { return pointName; }
    public void setPointName(String pointName) { this.pointName = pointName; }
    public String getCoordinateText() { return coordinateText; }
    public void setCoordinateText(String coordinateText) { this.coordinateText = coordinateText; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
}
