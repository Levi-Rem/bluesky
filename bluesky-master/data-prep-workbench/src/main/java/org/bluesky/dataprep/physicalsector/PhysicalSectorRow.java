package org.bluesky.dataprep.physicalsector;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 一条记录表示一个连续水平区域；名称允许重复。 */
public class PhysicalSectorRow {
    private String id;
    @NotBlank(message = "名称必填")
    private String name;
    @NotBlank(message = "类型必填")
    private String sectorType;
    @NotBlank(message = "组成方式必填")
    private String compositionMode;
    @NotBlank(message = "上限必填")
    private String upperLimit;
    @NotBlank(message = "下限必填")
    private String lowerLimit;
    private String sourceSubtype;
    private String sourceFlag;
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";
    @Valid
    private List<PhysicalSectorPointRow> points = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSectorType() { return sectorType; }
    public void setSectorType(String sectorType) { this.sectorType = sectorType; }
    public String getCompositionMode() { return compositionMode; }
    public void setCompositionMode(String compositionMode) { this.compositionMode = compositionMode; }
    public String getUpperLimit() { return upperLimit; }
    public void setUpperLimit(String upperLimit) { this.upperLimit = upperLimit; }
    public String getLowerLimit() { return lowerLimit; }
    public void setLowerLimit(String lowerLimit) { this.lowerLimit = lowerLimit; }
    public String getSourceSubtype() { return sourceSubtype; }
    public void setSourceSubtype(String sourceSubtype) { this.sourceSubtype = sourceSubtype; }
    public String getSourceFlag() { return sourceFlag; }
    public void setSourceFlag(String sourceFlag) { this.sourceFlag = sourceFlag; }
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
    public List<PhysicalSectorPointRow> getPoints() { return points; }
    public void setPoints(List<PhysicalSectorPointRow> points) { this.points = points; }
}
