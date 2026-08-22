package org.bluesky.dataprep.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.GeoJsonValidator;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeatherAreaService {
    static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "WIND_SHEAR", "MICROBURST", "JET_STREAM", "TURBULENCE",
            "ADVECTION_FOG", "RADIATION_FOG", "THUNDERSTORM"));
    private static final Pattern LIMIT = Pattern.compile("^S(\\d{4})$");
    private static final Pattern DMS = Pattern.compile(
            "^(\\d{2})(\\d{2})(\\d{2})([NS])(\\d{3})(\\d{2})(\\d{2})([EW])$",
            Pattern.CASE_INSENSITIVE);

    private final WeatherAreaMapper mapper;
    private final RevisionService revisionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WeatherAreaService(WeatherAreaMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<WeatherAreaRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = org.bluesky.dataprep.common.Paging.safePage(page, safeSize);
        return new PageResult<>(mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public WeatherAreaRow get(String id) {
        WeatherAreaRow row = mapper.findById(id);
        if (row == null) throw ApiException.notFound("气象区域不存在：" + id);
        return row;
    }

    @Transactional
    public WeatherAreaRow create(WeatherAreaRow row) {
        prepareAndValidate(row);
        row.setId(UUID.randomUUID().toString());
        row.setCode("WX-" + row.getId().substring(0, 8).toUpperCase());
        if (empty(row.getStatus())) row.setStatus("ENABLED");
        if (empty(row.getSourceType())) row.setSourceType("MANUAL");
        mapper.insert(row);
        revisionService.increment();
        return get(row.getId());
    }

    @Transactional
    public WeatherAreaRow update(String id, WeatherAreaRow body) {
        WeatherAreaRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        prepareAndValidate(body);
        body.setId(id);
        if (empty(body.getCode())) body.setCode(current.getCode());
        body.setSourceType(current.getSourceType());
        body.setSourceReference(current.getSourceReference());
        if (empty(body.getStatus())) body.setStatus(current.getStatus());
        Guards.requireUpdated(mapper.update(body), id);
        revisionService.increment();
        return get(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        WeatherAreaRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        revisionService.increment();
    }

    private void prepareAndValidate(WeatherAreaRow row) {
        if (!TYPES.contains(row.getWeatherType())) throw ApiException.badRequest("气象类型不合法");
        row.setArea(normalizeArea(row.getArea()));
        parseLimit(row.getLowerLimit(), true, row);
        parseLimit(row.getUpperLimit(), false, row);
        if (row.getLowerValue() > row.getUpperValue()
                && same(row.getLowerReference(), row.getUpperReference())) {
            throw ApiException.badRequest("下限不能高于上限");
        }
    }

    @SuppressWarnings("unchecked")
    private String normalizeArea(String area) {
        if (empty(area)) throw ApiException.badRequest("区域必填");
        String value = area.trim();
        if (value.startsWith("{")) {
            return GeoJsonValidator.validatePolygonGeometry(value, "气象区域");
        }

        List<List<Double>> ring = new ArrayList<>();
        for (String item : value.split("\\s+")) ring.add(dmsCoordinate(item.trim()));
        if (ring.size() < 3) throw ApiException.badRequest("气象区域至少需要三个边界点");
        ring.add(new ArrayList<>(ring.get(0)));
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", "Polygon");
        List<List<List<Double>>> coordinates = new ArrayList<>();
        coordinates.add(ring);
        geometry.put("coordinates", coordinates);
        try {
            return objectMapper.writeValueAsString(geometry);
        } catch (Exception ex) {
            throw ApiException.badRequest("气象区域转换失败：" + ex.getMessage());
        }
    }

    private static List<Double> dmsCoordinate(String value) {
        Matcher matcher = DMS.matcher(value);
        if (!matcher.matches()) throw ApiException.badRequest("DMS 坐标格式错误：" + value);
        int latMin = Integer.parseInt(matcher.group(2));
        int latSec = Integer.parseInt(matcher.group(3));
        int lonMin = Integer.parseInt(matcher.group(6));
        int lonSec = Integer.parseInt(matcher.group(7));
        if (latMin > 59 || latSec > 59 || lonMin > 59 || lonSec > 59) {
            throw ApiException.badRequest("DMS 坐标中的分、秒必须小于 60：" + value);
        }
        double latitude = Integer.parseInt(matcher.group(1)) + latMin / 60d + latSec / 3600d;
        double longitude = Integer.parseInt(matcher.group(5)) + lonMin / 60d + lonSec / 3600d;
        if ("S".equalsIgnoreCase(matcher.group(4))) latitude = -latitude;
        if ("W".equalsIgnoreCase(matcher.group(8))) longitude = -longitude;
        return coordinate(longitude, latitude);
    }

    private static List<Double> coordinate(double longitude, double latitude) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw ApiException.badRequest("经纬度超出范围");
        }
        List<Double> result = new ArrayList<>();
        result.add(longitude);
        result.add(latitude);
        return result;
    }

    private static void parseLimit(String value, boolean lower, WeatherAreaRow row) {
        Matcher matcher = LIMIT.matcher(value == null ? "" : value.trim().toUpperCase());
        if (!matcher.matches()) {
            throw ApiException.badRequest((lower ? "下限" : "上限") + "格式错误，应为 S 高度编码，例如 S0100");
        }
        String reference = "S";
        double number = Double.parseDouble(matcher.group(1));
        if (lower) {
            row.setLowerReference(reference);
            row.setLowerValue(number);
        } else {
            row.setUpperReference(reference);
            row.setUpperValue(number);
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
