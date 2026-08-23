package org.bluesky.training.display;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashSet;
import java.util.Set;

public class DisplaySettingsRequest {
    private String trackColor;
    private String selectedTrackColor;
    private String mapWaypointColor;
    private String mapAirwayColor;
    private String mapSectorColor;
    private String mapSectorFillColor;
    private String mapWeatherColor;
    private String mapWeatherFillColor;
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public String getTrackColor() { return trackColor; }
    public void setTrackColor(String trackColor) { this.trackColor = trackColor; }
    public String getSelectedTrackColor() { return selectedTrackColor; }
    public void setSelectedTrackColor(String selectedTrackColor) { this.selectedTrackColor = selectedTrackColor; }
    public String getMapWaypointColor() { return mapWaypointColor; }
    public void setMapWaypointColor(String mapWaypointColor) { this.mapWaypointColor = mapWaypointColor; }
    public String getMapAirwayColor() { return mapAirwayColor; }
    public void setMapAirwayColor(String mapAirwayColor) { this.mapAirwayColor = mapAirwayColor; }
    public String getMapSectorColor() { return mapSectorColor; }
    public void setMapSectorColor(String mapSectorColor) { this.mapSectorColor = mapSectorColor; }
    public String getMapSectorFillColor() { return mapSectorFillColor; }
    public void setMapSectorFillColor(String mapSectorFillColor) { this.mapSectorFillColor = mapSectorFillColor; }
    public String getMapWeatherColor() { return mapWeatherColor; }
    public void setMapWeatherColor(String mapWeatherColor) { this.mapWeatherColor = mapWeatherColor; }
    public String getMapWeatherFillColor() { return mapWeatherFillColor; }
    public void setMapWeatherFillColor(String mapWeatherFillColor) { this.mapWeatherFillColor = mapWeatherFillColor; }
    public Set<String> getUnknownFields() { return unknownFields; }

    @JsonAnySetter
    public void addUnknownField(String name, Object ignoredValue) {
        unknownFields.add(name);
    }
}
