package org.bluesky.training.display;

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
        Map<String, String> errors = validate(request);
        if (!errors.isEmpty()) throw new DisplaySettingsValidationException(errors);
        DisplaySettingsView normalized = new DisplaySettingsView(
                normalize(request.getTrackColor()), normalize(request.getSelectedTrackColor()),
                normalize(request.getMapWaypointColor()), normalize(request.getMapAirwayColor()),
                normalize(request.getMapSectorColor()), normalize(request.getMapSectorFillColor()),
                normalize(request.getMapWeatherColor()), normalize(request.getMapWeatherFillColor()));

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
                throw new DisplaySettingsPersistenceException("显示参数不存在: " + update.getKey());
            }
        }
        return normalized;
    }

    private Map<String, String> validate(DisplaySettingsRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null) {
            errors.put("displaySettings", "显示设置不能为空");
            return errors;
        }
        validateColor(errors, "trackColor", request.getTrackColor());
        validateColor(errors, "selectedTrackColor", request.getSelectedTrackColor());
        validateColor(errors, "mapWaypointColor", request.getMapWaypointColor());
        validateColor(errors, "mapAirwayColor", request.getMapAirwayColor());
        validateColor(errors, "mapSectorColor", request.getMapSectorColor());
        validateColor(errors, "mapSectorFillColor", request.getMapSectorFillColor());
        validateColor(errors, "mapWeatherColor", request.getMapWeatherColor());
        validateColor(errors, "mapWeatherFillColor", request.getMapWeatherFillColor());
        for (String field : request.getUnknownFields()) errors.put(field, "未知字段");
        return errors;
    }

    private void validateColor(Map<String, String> errors, String field, String value) {
        if (value == null || !COLOR.matcher(value.trim()).matches()) {
            errors.put(field, "必须使用 #RRGGBB 格式");
        }
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        if (value == null || !COLOR.matcher(value.trim()).matches()) return fallback;
        return normalize(value);
    }
}
