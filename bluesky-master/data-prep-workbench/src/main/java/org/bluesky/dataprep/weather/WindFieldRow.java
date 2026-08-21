package org.bluesky.dataprep.weather;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class WindFieldRow {

    private String id;
    @NotBlank(message = "业务编码必填")
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    @NotNull(message = "类型必填")
    private String windFieldType;
    private Double windDirectionDeg;
    private Double windSpeedMs;
    private String boundary;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";
    private List<WindPointRow> points = new ArrayList<>();

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

    public String getWindFieldType() {
        return windFieldType;
    }

    public void setWindFieldType(String windFieldType) {
        this.windFieldType = windFieldType;
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

    public String getBoundary() {
        return boundary;
    }

    public void setBoundary(String boundary) {
        this.boundary = boundary;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
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

    public List<WindPointRow> getPoints() {
        return points;
    }

    public void setPoints(List<WindPointRow> points) {
        this.points = points;
    }
}
