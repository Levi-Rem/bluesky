package org.bluesky.dataprep.performance;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/** 展平的机型高度层性能记录。 */
public class PerformanceRow {
    public String id;
    public String aircraftId;
    @NotBlank(message = "机型编码必填") public String code;
    @NotBlank(message = "名称必填") public String name;
    public String manufacturer;
    public String modelName;
    public String engineType;
    public String icaoWakeCategory;
    public String reacatWakeCategory;
    public Integer maximumTakeoffWeightKg;
    public String performanceCategory;
    public String status;

    public Integer sequenceNo;
    @NotBlank(message = "高度层必填") public String altitudeLayer;
    public String holdingSpeedLow;
    public String holdingSpeedMiddle;
    public String holdingSpeedHigh;
    public String takeoffSpeed;
    public Integer takeoffDurationS;
    public Integer takeoffAltitudeFt;
    public Double takeoffDistanceNm;
    public String landingSpeed;
    public Double radarCrossSection;
    public String maximumSpeed;
    public String maximumAltitudeLayer;
    public Integer maximumTurn;
    public Boolean machCapable;
    public Boolean jetAircraft;
    public Integer standardTurn;

    public Integer turnResponse1;
    public Integer turnResponse2;
    public Integer turnResponse3;
    public Integer accelerationResponse1;
    public Integer accelerationResponse2;
    public Integer accelerationResponse3;
    public Integer decelerationResponse1;
    public Integer decelerationResponse2;
    public Integer decelerationResponse3;
    public Integer climbResponse1;
    public Integer climbResponse2;
    public Integer climbResponse3;
    public Integer descentResponse1;
    public Integer descentResponse2;
    public Integer descentResponse3;

    public Integer climbRateFtMin;
    public Integer descentRateFtMin;
    public Integer accelerationKtsMin;
    public Integer decelerationKtsMin;
    public String cruiseSpeed;
    public String stallSpeed;
    public String climbSpeed;
    public String descentSpeed;

    public int revision;
    public LocalDateTime createdAt;
    public String createdBy = "local";
    public LocalDateTime updatedAt;
    public String updatedBy = "local";

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getManufacturer() { return manufacturer; }
    public String getModelName() { return modelName; }
    public String getEngineType() { return engineType; }
    public String getIcaoWakeCategory() { return icaoWakeCategory; }
    public String getReacatWakeCategory() { return reacatWakeCategory; }
    public Integer getMaximumTakeoffWeightKg() { return maximumTakeoffWeightKg; }
    public String getAltitudeLayer() { return altitudeLayer; }
    public String getCruiseSpeed() { return cruiseSpeed; }
    public Integer getClimbRateFtMin() { return climbRateFtMin; }
    public Integer getDescentRateFtMin() { return descentRateFtMin; }
    public int getRevision() { return revision; }

    public void setId(String value) { id = value; }
    public void setCode(String value) { code = value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT); }
    public void setName(String value) { name = value; }
    public void setManufacturer(String value) { manufacturer = value; }
    public void setModelName(String value) { modelName = value; }
    public void setEngineType(String value) { engineType = value; }
    public void setIcaoWakeCategory(String value) { icaoWakeCategory = value; }
    public void setReacatWakeCategory(String value) { reacatWakeCategory = value; }
    public void setMaximumTakeoffWeightKg(Integer value) { maximumTakeoffWeightKg = value; }
    public void setAltitudeLayer(String value) { altitudeLayer = value; }
    public void setCruiseSpeed(String value) { cruiseSpeed = value; }
    public void setClimbRateFtMin(Integer value) { climbRateFtMin = value; }
    public void setDescentRateFtMin(Integer value) { descentRateFtMin = value; }
    public void setRevision(int value) { revision = value; }
}
