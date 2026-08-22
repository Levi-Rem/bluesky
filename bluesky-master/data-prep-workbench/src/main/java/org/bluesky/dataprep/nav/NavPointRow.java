package org.bluesky.dataprep.nav;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 导航点行：含 BaseRecord 公共列。 */
public class NavPointRow {

    public static final String ENTITY = "nav-point";

    private String id;
    @NotBlank(message = "业务编码必填")
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    @NotNull(message = "类型必填")
    private String pointType;
    @NotNull(message = "经度必填")
    private Double longitude;
    @NotNull(message = "纬度必填")
    private Double latitude;
    private Integer elevationM;
    private Double frequencyMhz;
    private Double magneticVariationDeg;
    private String description;
    /** ASF 原始点类型与字段，便于和运行系统数据逐项追溯。 */
    private String sourcePointType;
    private String coordinateText;
    private String relevantFlag;
    private String applicableAirports;
    private String pilotFlag;
    private String dtiFlag;
    private String tfmFlag;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private Boolean deleted = Boolean.FALSE;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPointType() {
        return pointType;
    }

    public void setPointType(String pointType) {
        this.pointType = pointType;
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

    public Integer getElevationM() {
        return elevationM;
    }

    public void setElevationM(Integer elevationM) {
        this.elevationM = elevationM;
    }

    public Double getFrequencyMhz() {
        return frequencyMhz;
    }

    public void setFrequencyMhz(Double frequencyMhz) {
        this.frequencyMhz = frequencyMhz;
    }

    public Double getMagneticVariationDeg() {
        return magneticVariationDeg;
    }

    public void setMagneticVariationDeg(Double magneticVariationDeg) {
        this.magneticVariationDeg = magneticVariationDeg;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourcePointType() { return sourcePointType; }
    public void setSourcePointType(String sourcePointType) { this.sourcePointType = sourcePointType; }
    public String getCoordinateText() { return coordinateText; }
    public void setCoordinateText(String coordinateText) { this.coordinateText = coordinateText; }
    public String getRelevantFlag() { return relevantFlag; }
    public void setRelevantFlag(String relevantFlag) { this.relevantFlag = relevantFlag; }
    public String getApplicableAirports() { return applicableAirports; }
    public void setApplicableAirports(String applicableAirports) { this.applicableAirports = applicableAirports; }
    public String getPilotFlag() { return pilotFlag; }
    public void setPilotFlag(String pilotFlag) { this.pilotFlag = pilotFlag; }
    public String getDtiFlag() { return dtiFlag; }
    public void setDtiFlag(String dtiFlag) { this.dtiFlag = dtiFlag; }
    public String getTfmFlag() { return tfmFlag; }
    public void setTfmFlag(String tfmFlag) { this.tfmFlag = tfmFlag; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
