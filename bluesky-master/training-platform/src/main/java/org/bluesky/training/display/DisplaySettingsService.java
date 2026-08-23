package org.bluesky.training.display;

import org.bluesky.training.configuration.FieldValidationException;
import org.bluesky.training.persistence.DisplaySettingsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DisplaySettingsService {
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final DisplaySettingsView DEFAULTS = new DisplaySettingsView(
            "#3FAE6D", "#27E58D", "#7FD3FF", "#4AA8D8",
            "#D6A7FF", "#7B4DB3", "#FFCF66", "#D9822B");

    private final DisplaySettingsMapper mapper;

    public DisplaySettingsService(DisplaySettingsMapper mapper) {
        this.mapper = mapper;
    }

    public DisplaySettingsView defaults() {
        return DEFAULTS;
    }

    public DisplaySettingsView fromParameters(Map<String, String> values) {
        return new DisplaySettingsView(
                value(values, "ui.trackColor", DEFAULTS.getTrackColor()),
                value(values, "ui.selectedTrackColor", DEFAULTS.getSelectedTrackColor()),
                value(values, "ui.mapWaypointColor", DEFAULTS.getMapWaypointColor()),
                value(values, "ui.mapAirwayColor", DEFAULTS.getMapAirwayColor()),
                value(values, "ui.mapSectorColor", DEFAULTS.getMapSectorColor()),
                value(values, "ui.mapSectorFillColor", DEFAULTS.getMapSectorFillColor()),
                value(values, "ui.mapWeatherColor", DEFAULTS.getMapWeatherColor()),
                value(values, "ui.mapWeatherFillColor", DEFAULTS.getMapWeatherFillColor()));
    }

    @Transactional
    public DisplaySettingsView save(DisplaySettingsRequest request) {
        if (request == null) throw new FieldValidationException("displaySettings", "显示设置不能为空");
        DisplaySettingsView normalized = new DisplaySettingsView(
                color("trackColor", request.getTrackColor()),
                color("selectedTrackColor", request.getSelectedTrackColor()),
                color("mapWaypointColor", request.getMapWaypointColor()),
                color("mapAirwayColor", request.getMapAirwayColor()),
                color("mapSectorColor", request.getMapSectorColor()),
                color("mapSectorFillColor", request.getMapSectorFillColor()),
                color("mapWeatherColor", request.getMapWeatherColor()),
                color("mapWeatherFillColor", request.getMapWeatherFillColor()));

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("ui.trackColor", normalized.getTrackColor());
        updates.put("ui.selectedTrackColor", normalized.getSelectedTrackColor());
        updates.put("ui.mapWaypointColor", normalized.getMapWaypointColor());
        updates.put("ui.mapAirwayColor", normalized.getMapAirwayColor());
        updates.put("ui.mapSectorColor", normalized.getMapSectorColor());
        updates.put("ui.mapSectorFillColor", normalized.getMapSectorFillColor());
        updates.put("ui.mapWeatherColor", normalized.getMapWeatherColor());
        updates.put("ui.mapWeatherFillColor", normalized.getMapWeatherFillColor());
        for (Map.Entry<String, String> update : updates.entrySet()) {
            if (mapper.update(update.getKey(), update.getValue()) != 1) {
                throw new IllegalStateException("显示参数不存在: " + update.getKey());
            }
        }
        return normalized;
    }

    private String color(String field, String value) {
        if (value == null || !COLOR.matcher(value.trim()).matches()) {
            throw new FieldValidationException(field, "必须使用 #RRGGBB 格式");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
