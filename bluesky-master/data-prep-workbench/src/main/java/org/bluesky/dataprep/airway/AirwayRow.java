package org.bluesky.dataprep.airway;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AirwayRow {

    private String id;
    @NotBlank(message = "航路编码必填")
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    @NotNull(message = "方向必填")
    private String airwayDirection;
    private Double lowerValue;
    private String lowerReference;
    private Double upperValue;
    private String upperReference;
    private String cruiseLevelRule;
    private String rnavCapability;
    private String rnavCapabilityPost2012;
    private String rnpCapabilityPost2012;
    private String rvsmLevel;
    /** CODED_ROUTE / SID / STAR。 */
    private String routeType = "CODED_ROUTE";
    private String procedureAirport;
    private String procedureProfile;
    private String procedureRunway;
    private String procedureDirection;
    private String procedureOperation;
    private String eligibleRoute;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";
    private List<AirwaySegmentRow> segments = new ArrayList<>();

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

    public String getAirwayDirection() {
        return airwayDirection;
    }

    public void setAirwayDirection(String airwayDirection) {
        this.airwayDirection = airwayDirection;
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

    public String getCruiseLevelRule() { return cruiseLevelRule; }
    public void setCruiseLevelRule(String cruiseLevelRule) { this.cruiseLevelRule = cruiseLevelRule; }
    public String getRnavCapability() { return rnavCapability; }
    public void setRnavCapability(String rnavCapability) { this.rnavCapability = rnavCapability; }
    public String getRnavCapabilityPost2012() { return rnavCapabilityPost2012; }
    public void setRnavCapabilityPost2012(String value) { this.rnavCapabilityPost2012 = value; }
    public String getRnpCapabilityPost2012() { return rnpCapabilityPost2012; }
    public void setRnpCapabilityPost2012(String value) { this.rnpCapabilityPost2012 = value; }
    public String getRvsmLevel() { return rvsmLevel; }
    public void setRvsmLevel(String rvsmLevel) { this.rvsmLevel = rvsmLevel; }
    public String getRouteType() { return routeType; }
    public void setRouteType(String routeType) { this.routeType = routeType; }
    public String getProcedureAirport() { return procedureAirport; }
    public void setProcedureAirport(String value) { this.procedureAirport = value; }
    public String getProcedureProfile() { return procedureProfile; }
    public void setProcedureProfile(String value) { this.procedureProfile = value; }
    public String getProcedureRunway() { return procedureRunway; }
    public void setProcedureRunway(String value) { this.procedureRunway = value; }
    public String getProcedureDirection() { return procedureDirection; }
    public void setProcedureDirection(String value) { this.procedureDirection = value; }
    public String getProcedureOperation() { return procedureOperation; }
    public void setProcedureOperation(String value) { this.procedureOperation = value; }
    public String getEligibleRoute() { return eligibleRoute; }
    public void setEligibleRoute(String value) { this.eligibleRoute = value; }

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

    public List<AirwaySegmentRow> getSegments() {
        return segments;
    }

    public void setSegments(List<AirwaySegmentRow> segments) {
        this.segments = segments;
    }
}
