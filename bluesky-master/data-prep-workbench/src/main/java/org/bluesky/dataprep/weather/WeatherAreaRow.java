package org.bluesky.dataprep.weather;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/** 统一气象区域：名称、类型、区域和垂直范围。 */
public class WeatherAreaRow {
    private String id;
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    @NotBlank(message = "类型必填")
    private String weatherType;
    @NotBlank(message = "区域必填")
    private String area;
    private Double lowerValue;
    private String lowerReference;
    private Double upperValue;
    private String upperReference;
    @NotBlank(message = "下限必填")
    private String lowerLimit;
    @NotBlank(message = "上限必填")
    private String upperLimit;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWeatherType() { return weatherType; }
    public void setWeatherType(String weatherType) { this.weatherType = weatherType; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public Double getLowerValue() { return lowerValue; }
    public void setLowerValue(Double lowerValue) { this.lowerValue = lowerValue; }
    public String getLowerReference() { return lowerReference; }
    public void setLowerReference(String lowerReference) { this.lowerReference = lowerReference; }
    public Double getUpperValue() { return upperValue; }
    public void setUpperValue(Double upperValue) { this.upperValue = upperValue; }
    public String getUpperReference() { return upperReference; }
    public void setUpperReference(String upperReference) { this.upperReference = upperReference; }
    public String getLowerLimit() { return lowerLimit == null ? formatLimit(lowerReference, lowerValue) : lowerLimit; }
    public void setLowerLimit(String lowerLimit) { this.lowerLimit = lowerLimit; }
    public String getUpperLimit() { return upperLimit == null ? formatLimit(upperReference, upperValue) : upperLimit; }
    public void setUpperLimit(String upperLimit) { this.upperLimit = upperLimit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    private static String formatLimit(String reference, Double value) {
        if (value == null) return null;
        boolean integer = value.doubleValue() == Math.rint(value.doubleValue());
        String number = "S".equalsIgnoreCase(reference) && integer
                ? String.format("%04d", value.longValue())
                : integer ? String.valueOf(value.longValue()) : String.valueOf(value);
        return (reference == null ? "" : reference) + number;
    }
}
