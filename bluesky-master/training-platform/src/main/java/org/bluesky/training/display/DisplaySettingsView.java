package org.bluesky.training.display;

public final class DisplaySettingsView {
    private final String trackColor;
    private final String selectedTrackColor;
    private final String mapWaypointColor;
    private final String mapAirwayColor;
    private final String mapSectorColor;
    private final String mapSectorFillColor;
    private final String mapWeatherColor;
    private final String mapWeatherFillColor;

    public DisplaySettingsView(String trackColor, String selectedTrackColor,
            String mapWaypointColor, String mapAirwayColor, String mapSectorColor,
            String mapSectorFillColor, String mapWeatherColor, String mapWeatherFillColor) {
        this.trackColor = trackColor;
        this.selectedTrackColor = selectedTrackColor;
        this.mapWaypointColor = mapWaypointColor;
        this.mapAirwayColor = mapAirwayColor;
        this.mapSectorColor = mapSectorColor;
        this.mapSectorFillColor = mapSectorFillColor;
        this.mapWeatherColor = mapWeatherColor;
        this.mapWeatherFillColor = mapWeatherFillColor;
    }

    public String getTrackColor() { return trackColor; }
    public String getSelectedTrackColor() { return selectedTrackColor; }
    public String getMapWaypointColor() { return mapWaypointColor; }
    public String getMapAirwayColor() { return mapAirwayColor; }
    public String getMapSectorColor() { return mapSectorColor; }
    public String getMapSectorFillColor() { return mapSectorFillColor; }
    public String getMapWeatherColor() { return mapWeatherColor; }
    public String getMapWeatherFillColor() { return mapWeatherFillColor; }
}
