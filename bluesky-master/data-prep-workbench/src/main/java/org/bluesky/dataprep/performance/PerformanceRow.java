package org.bluesky.dataprep.performance;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public class PerformanceRow {

    private String id;
    @NotBlank(message = "机型编码必填")
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    private String manufacturer;
    private String modelName;
    private String performanceSource = "MANUAL";
    private String engineType;
    private String wakeTurbulenceCategory;
    private Integer maximumTakeoffWeightKg;
    private Integer maximumAltitudeFt;
    private Double maximumMach;
    private Double defaultBankAngleDeg;
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

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getPerformanceSource() {
        return performanceSource;
    }

    public void setPerformanceSource(String performanceSource) {
        this.performanceSource = performanceSource;
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        this.engineType = engineType;
    }

    public String getWakeTurbulenceCategory() {
        return wakeTurbulenceCategory;
    }

    public void setWakeTurbulenceCategory(String wakeTurbulenceCategory) {
        this.wakeTurbulenceCategory = wakeTurbulenceCategory;
    }

    public Integer getMaximumTakeoffWeightKg() {
        return maximumTakeoffWeightKg;
    }

    public void setMaximumTakeoffWeightKg(Integer maximumTakeoffWeightKg) {
        this.maximumTakeoffWeightKg = maximumTakeoffWeightKg;
    }

    public Integer getMaximumAltitudeFt() {
        return maximumAltitudeFt;
    }

    public void setMaximumAltitudeFt(Integer maximumAltitudeFt) {
        this.maximumAltitudeFt = maximumAltitudeFt;
    }

    public Double getMaximumMach() {
        return maximumMach;
    }

    public void setMaximumMach(Double maximumMach) {
        this.maximumMach = maximumMach;
    }

    public Double getDefaultBankAngleDeg() {
        return defaultBankAngleDeg;
    }

    public void setDefaultBankAngleDeg(Double defaultBankAngleDeg) {
        this.defaultBankAngleDeg = defaultBankAngleDeg;
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
