package org.bluesky.dataprep.airspace;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

public class AirspaceRow {

    private String id;
    @NotBlank(message = "业务编码必填")
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    @NotNull(message = "类型必填")
    private String airspaceType;
    /** GeoJSON Polygon / MultiPolygon */
    private String boundary;
    private Double lowerValue;
    private String lowerReference;
    private Double upperValue;
    private String upperReference;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
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

    public String getAirspaceType() {
        return airspaceType;
    }

    public void setAirspaceType(String airspaceType) {
        this.airspaceType = airspaceType;
    }

    public String getBoundary() {
        return boundary;
    }

    public void setBoundary(String boundary) {
        this.boundary = boundary;
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

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDateTime getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDateTime validTo) {
        this.validTo = validTo;
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
}
