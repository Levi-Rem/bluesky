package org.bluesky.dataprep.radar;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class RadarSiteRow {

    private String id;
    private String code;
    private String name;
    private Integer sac;
    private Integer sic;
    private Double longitude;
    private Double latitude;
    private Integer altitudeM;
    private Double maximumRangeNm;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";
    /** 只读展示：该站绑定的通道编码。 */
    private List<String> boundChannelCodes = new ArrayList<>();

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

    public Integer getSac() {
        return sac;
    }

    public void setSac(Integer sac) {
        this.sac = sac;
    }

    public Integer getSic() {
        return sic;
    }

    public void setSic(Integer sic) {
        this.sic = sic;
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

    public Double getMaximumRangeNm() {
        return maximumRangeNm;
    }

    public void setMaximumRangeNm(Double maximumRangeNm) {
        this.maximumRangeNm = maximumRangeNm;
    }

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

    public List<String> getBoundChannelCodes() {
        return boundChannelCodes;
    }

    public void setBoundChannelCodes(List<String> boundChannelCodes) {
        this.boundChannelCodes = boundChannelCodes;
    }
}
