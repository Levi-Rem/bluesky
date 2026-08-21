package org.bluesky.dataprep.weather;

/** 气象页混合列表行：风场 / 机场气象 / 重要天气区域三类合并展示。 */
public class WeatherSummaryRow {

    private String id;
    private String code;
    private String name;
    /** WIND_FIELD / AIRPORT_WEATHER / SIG_WEATHER */
    private String category;
    /** 展示用中文：三维风场 / 机场气象 / 重要天气区域 等 */
    private String dataType;
    private String relatedArea;
    private String effectiveFrom;
    private String effectiveTo;
    private String status;

    public WeatherSummaryRow() {
    }

    public WeatherSummaryRow(String id, String code, String name, String category, String dataType,
                             String relatedArea, String effectiveFrom, String effectiveTo, String status) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.category = category;
        this.dataType = dataType;
        this.relatedArea = relatedArea;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.status = status;
    }

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getRelatedArea() {
        return relatedArea;
    }

    public void setRelatedArea(String relatedArea) {
        this.relatedArea = relatedArea;
    }

    public String getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(String effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(String effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
