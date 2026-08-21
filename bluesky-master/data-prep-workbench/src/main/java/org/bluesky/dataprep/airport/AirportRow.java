package org.bluesky.dataprep.airport;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AirportRow {

    private String id;
    @NotBlank(message = "业务编码必填")
    private String code;
    @NotBlank(message = "名称必填")
    private String name;
    private String icao;
    private String iata;
    private String country;
    private String airportGrade;
    private Integer maxRunwayLengthM;
    @NotNull(message = "经度必填")
    private Double longitude;
    @NotNull(message = "纬度必填")
    private Double latitude;
    private Integer elevationM;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";
    /** 明细/保存时的跑道子表（replace-all）。 */
    private List<RunwayRow> runways = new ArrayList<>();

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

    public String getIcao() {
        return icao;
    }

    public void setIcao(String icao) {
        this.icao = icao;
    }

    public String getIata() {
        return iata;
    }

    public void setIata(String iata) {
        this.iata = iata;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getAirportGrade() {
        return airportGrade;
    }

    public void setAirportGrade(String airportGrade) {
        this.airportGrade = airportGrade;
    }

    public Integer getMaxRunwayLengthM() {
        return maxRunwayLengthM;
    }

    public void setMaxRunwayLengthM(Integer maxRunwayLengthM) {
        this.maxRunwayLengthM = maxRunwayLengthM;
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

    public List<RunwayRow> getRunways() {
        return runways;
    }

    public void setRunways(List<RunwayRow> runways) {
        this.runways = runways;
    }
}
