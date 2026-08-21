package org.bluesky.dataprep.weather;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.bluesky.dataprep.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 气象数据页：风场（一期可编辑）+ 机场气象/重要天气区域（二期，只读展示）。 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WindFieldService windFieldService;
    private final WeatherRefMapper refMapper;

    public WeatherController(WindFieldService windFieldService, WeatherRefMapper refMapper) {
        this.windFieldService = windFieldService;
        this.refMapper = refMapper;
    }

    @GetMapping
    public PageResult<WeatherSummaryRow> list(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        List<WeatherSummaryRow> all = new ArrayList<>();
        for (WindFieldRow wind : windFieldService.list(0, 200).getItems()) {
            String typeName = "GLOBAL_CONSTANT".equals(wind.getWindFieldType()) ? "恒定风"
                    : "TWO_DIMENSIONAL".equals(wind.getWindFieldType()) ? "二维风场" : "三维风场";
            all.add(new WeatherSummaryRow(wind.getId(), wind.getCode(), wind.getName(),
                    "WIND_FIELD", typeName, wind.getBoundary() == null ? "—" : "区域边界",
                    format(wind.getEffectiveFrom()), format(wind.getEffectiveTo()),
                    "ENABLED".equals(wind.getStatus()) ? "有效" : "停用"));
        }
        for (Map<String, Object> row : refMapper.selectAirportWeather()) {
            all.add(new WeatherSummaryRow(
                    String.valueOf(row.get("id")), String.valueOf(row.get("code")),
                    String.valueOf(row.get("name")), "AIRPORT_WEATHER", "机场气象",
                    String.valueOf(row.get("icao")),
                    format(row.get("validFrom")), format(row.get("validTo")),
                    "ENABLED".equals(row.get("status")) ? "有效" : "停用"));
        }
        for (Map<String, Object> row : refMapper.selectSigWeather()) {
            all.add(new WeatherSummaryRow(
                    String.valueOf(row.get("id")), String.valueOf(row.get("code")),
                    String.valueOf(row.get("name")), "SIG_WEATHER", "重要天气区域", "—",
                    format(row.get("validFrom")), format(row.get("validTo")),
                    "ENABLED".equals(row.get("status")) ? "有效" : "停用"));
        }
        all.sort(Comparator.comparing(WeatherSummaryRow::getCode));

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageResult<>(new ArrayList<>(all.subList(from, to)), safePage, safeSize, all.size());
    }

    private String format(Object dateTime) {
        return dateTime == null ? "全天" : String.valueOf(dateTime);
    }

    @Mapper
    public interface WeatherRefMapper {

        @Select("SELECT w.id, w.code, w.name, a.icao, w.valid_from AS \"validFrom\", w.valid_to AS \"validTo\", w.status "
                + "FROM airport_weather w JOIN airport a ON a.id = w.airport_id "
                + "WHERE w.deleted = FALSE")
        List<Map<String, Object>> selectAirportWeather();

        @Select("SELECT id, code, name, valid_from AS \"validFrom\", valid_to AS \"validTo\", status "
                + "FROM significant_weather_area WHERE deleted = FALSE")
        List<Map<String, Object>> selectSigWeather();
    }
}
