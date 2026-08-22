package org.bluesky.dataprep.excel;

import org.bluesky.dataprep.airport.AirportRow;
import org.bluesky.dataprep.airport.AirportService;
import org.bluesky.dataprep.airport.RunwayRow;
import org.bluesky.dataprep.airspace.AirspaceRow;
import org.bluesky.dataprep.airspace.AirspaceService;
import org.bluesky.dataprep.airway.AirwayRow;
import org.bluesky.dataprep.airway.AirwaySegmentRow;
import org.bluesky.dataprep.airway.AirwayService;
import org.bluesky.dataprep.nav.NavPointRow;
import org.bluesky.dataprep.nav.NavPointService;
import org.bluesky.dataprep.performance.PerformanceRow;
import org.bluesky.dataprep.performance.PerformanceService;
import org.bluesky.dataprep.radar.AsterixChannelRow;
import org.bluesky.dataprep.radar.RadarService;
import org.bluesky.dataprep.radar.RadarSiteRow;
import org.bluesky.dataprep.weather.WeatherAreaRow;
import org.bluesky.dataprep.weather.WeatherAreaService;
import org.bluesky.dataprep.weather.WindFieldRow;
import org.bluesky.dataprep.weather.WindFieldService;
import org.bluesky.dataprep.weather.WindPointRow;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.bluesky.dataprep.excel.ExcelColumn.col;

/** 实体的 Excel 模式注册表：列定义 + 样例 + 导出行构建 + 导入应用。 */
@Component
public class EntitySchemas {

    private final NavPointService navPointService;
    private final AirportService airportService;
    private final AirspaceService airspaceService;
    private final AirwayService airwayService;
    private final WeatherAreaService weatherAreaService;
    private final WindFieldService windFieldService;
    private final PerformanceService performanceService;
    private final RadarService radarService;

    private final Map<String, EntitySchema<?>> schemas = new LinkedHashMap<>();

    public EntitySchemas(NavPointService navPointService, AirportService airportService,
                         AirspaceService airspaceService, AirwayService airwayService,
                         WeatherAreaService weatherAreaService, WindFieldService windFieldService,
                         PerformanceService performanceService,
                         RadarService radarService) {
        this.navPointService = navPointService;
        this.airportService = airportService;
        this.airspaceService = airspaceService;
        this.airwayService = airwayService;
        this.weatherAreaService = weatherAreaService;
        this.windFieldService = windFieldService;
        this.performanceService = performanceService;
        this.radarService = radarService;
        registerNavPoint();
        registerAirport();
        registerAirspace();
        registerAirway();
        registerWeatherArea();
        registerWindField();
        registerPerformance();
        registerRadarSite();
        registerAsterixChannel();
    }

    public EntitySchema<?> schema(String entity) {
        EntitySchema<?> schema = schemas.get(entity);
        if (schema == null) {
            throw org.bluesky.dataprep.common.ApiException.notFound("不支持的实体：" + entity);
        }
        return schema;
    }

    public List<String> entities() {
        return new ArrayList<>(schemas.keySet());
    }

    // ---- 各实体注册 ----

    private void registerNavPoint() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "编码*"), col("name", "名称*"), col("pointType", "类型*(FIX/VOR/NDB/DME/VOR_DME/ILS/OTHER)"),
                col("longitude", "经度*"), col("latitude", "纬度*"), col("elevationM", "海拔(米)"),
                col("frequencyMhz", "频率(MHz)"), col("description", "描述"));
        schemas.put("nav-point", new EntitySchema<>(
                "nav-point", "导航点", cols,
                Arrays.asList("PUD", "浦东VOR/DME", "VOR", "121.8", "31.2", "4", "113.2", "原型样例行"),
                (page, size) -> navPointService.list(page, size).getItems(),
                NavPointRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), row.getPointType(),
                        str(row.getLongitude()), str(row.getLatitude()),
                        str(row.getElevationM()), str(row.getFrequencyMhz()), nz(row.getDescription())),
                (fields, existing) -> {
                    NavPointRow row = new NavPointRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setPointType(req(fields, "pointType", "类型"));
                    row.setLongitude(dbl(fields, "longitude", "经度", true));
                    row.setLatitude(dbl(fields, "latitude", "纬度", true));
                    row.setElevationM(intg(fields, "elevationM", "海拔", false));
                    row.setFrequencyMhz(dbl(fields, "frequencyMhz", "频率", false));
                    row.setDescription(fields.get("description"));
                    if (existing != null) {
                        NavPointRow prior = (NavPointRow) existing;
                        row.setRevision(prior.getRevision());
                        return navPointService.update(prior.getId(), row);
                    }
                    return navPointService.create(row);
                }));
    }

    private void registerAirport() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "编码*"), col("name", "名称*"), col("icao", "ICAO"), col("iata", "IATA"),
                col("country", "国家"), col("airportGrade", "等级"), col("longitude", "经度*"),
                col("latitude", "纬度*"), col("elevationM", "标高(米)"), col("maxRunwayLengthM", "最长跑道(米)"),
                col("runways", "跑道(跑道号:长度:宽度:真方位:道面:入口1经度:入口1纬度:入口2经度:入口2纬度:磁方位:状态;…)"));
        schemas.put("airport", new EntitySchema<>(
                "airport", "机场", cols,
                Arrays.asList("ZBTJ", "天津滨海", "ZBTJ", "TSN", "CN", "4F", "117.35", "39.12", "3", "3600",
                        "16R/34L:3600:60:162:ASPHALT:117.34:39.11:117.36:39.13:155:ACTIVE"),
                (page, size) -> airportService.list(page, size).getItems().stream()
                        .map(row -> airportService.get(row.getId())).collect(Collectors.toList()),
                AirportRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), nz(row.getIcao()), nz(row.getIata()),
                        nz(row.getCountry()), nz(row.getAirportGrade()),
                        str(row.getLongitude()), str(row.getLatitude()), str(row.getElevationM()),
                        str(row.getMaxRunwayLengthM()), packRunways(row)),
                (fields, existing) -> {
                    AirportRow row = new AirportRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setIcao(fields.get("icao"));
                    row.setIata(fields.get("iata"));
                    row.setCountry(fields.get("country"));
                    row.setAirportGrade(fields.get("airportGrade"));
                    row.setLongitude(dbl(fields, "longitude", "经度", true));
                    row.setLatitude(dbl(fields, "latitude", "纬度", true));
                    row.setElevationM(intg(fields, "elevationM", "标高", false));
                    row.setMaxRunwayLengthM(intg(fields, "maxRunwayLengthM", "最长跑道", false));
                    row.setRunways(parseRunways(fields.get("runways")));
                    if (existing != null) {
                        AirportRow prior = (AirportRow) existing;
                        row.setRevision(prior.getRevision());
                        return airportService.update(prior.getId(), row);
                    }
                    return airportService.create(row);
                }));
    }

    private void registerAirspace() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "编码*"), col("name", "名称*"),
                col("airspaceType", "类型*(FIR/TMA/CTR/CTA/RESTRICTED/DANGER/PROHIBITED)"),
                col("boundary", "边界GeoJSON*"), col("lowerValue", "下限值"), col("lowerReference", "下限基准"),
                col("upperValue", "上限值"), col("upperReference", "上限基准"));
        schemas.put("airspace", new EntitySchema<>(
                "airspace", "空域", cols,
                Arrays.asList("T-100", "示例管制区", "TMA",
                        "{\"type\":\"Polygon\",\"coordinates\":[[[120.0,30.0],[121.0,30.0],[121.0,31.0],[120.0,31.0],[120.0,30.0]]]}",
                        "1200", "MSL", "6000", "MSL"),
                (page, size) -> airspaceService.list(page, size).getItems(),
                AirspaceRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), row.getAirspaceType(), nz(row.getBoundary()),
                        str(row.getLowerValue()), nz(row.getLowerReference()),
                        str(row.getUpperValue()), nz(row.getUpperReference())),
                (fields, existing) -> {
                    AirspaceRow row = new AirspaceRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setAirspaceType(req(fields, "airspaceType", "类型"));
                    row.setBoundary(req(fields, "boundary", "边界GeoJSON"));
                    row.setLowerValue(dbl(fields, "lowerValue", "下限值", false));
                    row.setLowerReference(fields.get("lowerReference"));
                    row.setUpperValue(dbl(fields, "upperValue", "上限值", false));
                    row.setUpperReference(fields.get("upperReference"));
                    if (existing != null) {
                        AirspaceRow prior = (AirspaceRow) existing;
                        row.setRevision(prior.getRevision());
                        return airspaceService.update(prior.getId(), row);
                    }
                    return airspaceService.create(row);
                }));
    }

    private void registerAirway() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "名称*"), col("routePath", "航路*(点编码空格分隔)"),
                col("routeType", "类型*(CODED_ROUTE/SID/STAR)"));
        schemas.put("airway", new EntitySchema<>(
                "airway", "航路", cols,
                Arrays.asList("T-800", "PUD SASAN AND", "CODED_ROUTE"),
                (page, size) -> airwayService.list(page, size).getItems(),
                AirwayRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), packRoutePath(row), row.getRouteType()),
                (fields, existing) -> {
                    Map<String, String> navIndex = navCodeIndex();
                    AirwayRow row = new AirwayRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(row.getCode());
                    row.setRouteType(req(fields, "routeType", "类型").toUpperCase(java.util.Locale.ROOT));
                    row.setAirwayDirection("CODED_ROUTE".equals(row.getRouteType()) ? "TWO_WAY" : "ONE_WAY");
                    row.setSegments(parseRoutePath(fields.get("routePath"), navIndex));
                    if (existing != null) {
                        AirwayRow prior = (AirwayRow) existing;
                        row.setLowerValue(prior.getLowerValue());
                        row.setLowerReference(prior.getLowerReference());
                        row.setUpperValue(prior.getUpperValue());
                        row.setUpperReference(prior.getUpperReference());
                        row.setRevision(prior.getRevision());
                        return airwayService.update(prior.getId(), row);
                    }
                    return airwayService.create(row);
                }));
    }

    private void registerWindField() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "编码*"), col("name", "名称*"),
                col("windFieldType", "类型*(GLOBAL_CONSTANT/TWO_DIMENSIONAL/THREE_DIMENSIONAL)"),
                col("windDirectionDeg", "风向(度)"), col("windSpeedMs", "风速(米/秒)"),
                col("boundary", "区域边界GeoJSON"), col("effectiveFrom", "生效自(yyyy-MM-ddTHH:mm:ss)"),
                col("effectiveTo", "生效至"), col("points", "风场点(经度:纬度:高度:风向:风速;…)"));
        schemas.put("wind-field", new EntitySchema<>(
                "wind-field", "风场", cols,
                Arrays.asList("WIND-T100", "示例风场", "THREE_DIMENSIONAL", "", "",
                        "", "2026-08-21T08:00:00", "2026-08-21T20:00:00",
                        "121.5:31.2:2000:90:5.5;121.6:31.3:3000:100:7.0"),
                (page, size) -> windFieldService.list(page, size).getItems().stream()
                        .map(row -> windFieldService.get(row.getId())).collect(Collectors.toList()),
                WindFieldRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), row.getWindFieldType(),
                        str(row.getWindDirectionDeg()), str(row.getWindSpeedMs()), nz(row.getBoundary()),
                        time(row.getEffectiveFrom()), time(row.getEffectiveTo()), packPoints(row)),
                (fields, existing) -> {
                    WindFieldRow row = new WindFieldRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setWindFieldType(req(fields, "windFieldType", "类型"));
                    row.setWindDirectionDeg(dbl(fields, "windDirectionDeg", "风向", false));
                    row.setWindSpeedMs(dbl(fields, "windSpeedMs", "风速", false));
                    row.setBoundary(blankToNull(fields.get("boundary")));
                    row.setEffectiveFrom(dateTime(fields.get("effectiveFrom"), "生效自"));
                    row.setEffectiveTo(dateTime(fields.get("effectiveTo"), "生效至"));
                    row.setPoints(parsePoints(fields.get("points")));
                    if (existing != null) {
                        WindFieldRow prior = (WindFieldRow) existing;
                        row.setRevision(prior.getRevision());
                        return windFieldService.update(prior.getId(), row);
                    }
                    return windFieldService.create(row);
                }));
    }

    private void registerPerformance() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "机型编码*"), col("name", "名称*"), col("manufacturer", "制造商"),
                col("modelName", "型号"), col("engineType", "发动机类型"),
                col("icaoWakeCategory", "ICAO尾流类别"), col("reacatWakeCategory", "RECAT尾流类别"),
                col("maximumTakeoffWeightKg", "最大起飞重量(千克)"),
                col("performanceCategory", "性能类别"), col("altitudeLayer", "高度层*"),
                col("cruiseSpeed", "巡航速度"), col("climbRateFtMin", "爬升率(ft/min)"),
                col("descentRateFtMin", "下降率(ft/min)"));
        schemas.put("performance", new EntitySchema<>(
                "performance", "机型性能", cols,
                Arrays.asList("B738", "B738", "BOEING", "737-800", "CFM56", "M", "M",
                        "79010", "H", "F100", "N0300", "2100", "1700"),
                (page, size) -> performanceService.list(page, size).getItems(),
                row -> performanceExcelKey(row.getCode(), row.getIcaoWakeCategory(),
                        row.getReacatWakeCategory(), row.getAltitudeLayer()),
                row -> Arrays.asList(
                        row.getCode(), row.getName(), nz(row.getManufacturer()), nz(row.getModelName()),
                        nz(row.getEngineType()), nz(row.getIcaoWakeCategory()), nz(row.getReacatWakeCategory()),
                        str(row.getMaximumTakeoffWeightKg()), nz(row.performanceCategory), row.getAltitudeLayer(),
                        nz(row.getCruiseSpeed()), str(row.getClimbRateFtMin()), str(row.getDescentRateFtMin())),
                (fields, existing) -> {
                    PerformanceRow row = new PerformanceRow();
                    if (existing != null) {
                        PerformanceRow prior = (PerformanceRow) existing;
                        copyPerformance(prior, row);
                    }
                    row.setCode(req(fields, "code", "机型编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setManufacturer(fields.get("manufacturer"));
                    row.setModelName(fields.get("modelName"));
                    row.setEngineType(fields.get("engineType"));
                    row.setIcaoWakeCategory(fields.get("icaoWakeCategory"));
                    row.setReacatWakeCategory(fields.get("reacatWakeCategory"));
                    row.setMaximumTakeoffWeightKg(intg(fields, "maximumTakeoffWeightKg", "最大起飞重量", false));
                    row.performanceCategory = blankToNull(fields.get("performanceCategory"));
                    row.setAltitudeLayer(req(fields, "altitudeLayer", "高度层"));
                    row.setCruiseSpeed(blankToNull(fields.get("cruiseSpeed")));
                    row.setClimbRateFtMin(intg(fields, "climbRateFtMin", "爬升率", false));
                    row.setDescentRateFtMin(intg(fields, "descentRateFtMin", "下降率", false));
                    if (existing != null) {
                        PerformanceRow prior = (PerformanceRow) existing;
                        row.setRevision(prior.getRevision());
                        return performanceService.update(prior.getId(), row);
                    }
                    return performanceService.create(row);
                }));
    }

    private void registerRadarSite() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "编码*"), col("name", "名称*"), col("sac", "SAC(0-255)"), col("sic", "SIC(0-255)"),
                col("longitude", "经度*"), col("latitude", "纬度*"), col("altitudeM", "天线海拔(米)"),
                col("maximumRangeNm", "最大作用距离(海里)"));
        schemas.put("radar-site", new EntitySchema<>(
                "radar-site", "逻辑雷达站", cols,
                Arrays.asList("RDR-SHA-02", "虹桥场监雷达", "1", "22", "121.3", "31.2", "10", "60"),
                (page, size) -> radarService.listSites(page, size).getItems(),
                RadarSiteRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), str(row.getSac()), str(row.getSic()),
                        str(row.getLongitude()), str(row.getLatitude()),
                        str(row.getAltitudeM()), str(row.getMaximumRangeNm())),
                (fields, existing) -> {
                    RadarSiteRow row = new RadarSiteRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setSac(intg(fields, "sac", "SAC", false));
                    row.setSic(intg(fields, "sic", "SIC", false));
                    row.setLongitude(dbl(fields, "longitude", "经度", true));
                    row.setLatitude(dbl(fields, "latitude", "纬度", true));
                    row.setAltitudeM(intg(fields, "altitudeM", "天线海拔", false));
                    row.setMaximumRangeNm(dbl(fields, "maximumRangeNm", "最大作用距离", false));
                    if (existing != null) {
                        RadarSiteRow prior = (RadarSiteRow) existing;
                        row.setRevision(prior.getRevision());
                        return radarService.updateSite(prior.getId(), row);
                    }
                    return radarService.createSite(row);
                }));
    }

    private void registerAsterixChannel() {
        List<ExcelColumn> cols = Arrays.asList(
                col("code", "编码*"), col("name", "名称*"), col("category", "类别*(CAT021/CAT048/CAT062)"),
                col("edition", "版本"), col("periodMs", "发送周期(毫秒)"),
                col("transmissionMode", "传输方式(UNICAST/MULTICAST)"), col("destinationIp", "目标IP"),
                col("destinationPort", "目标端口"), col("ttl", "TTL"), col("maximumDatagramBytes", "最大报文(字节)"),
                col("channelEnabled", "通道启用(true/false)"), col("boundSites", "绑定雷达站(编码;…)"));
        schemas.put("asterix-channel", new EntitySchema<>(
                "asterix-channel", "ASTERIX通道", cols,
                Arrays.asList("CH-048-02", "虹桥CAT048", "CAT048", "1.32", "4000", "MULTICAST",
                        "239.1.1.14", "5004", "1", "1400", "true", "RDR-SHA-01"),
                (page, size) -> radarService.listChannels(page, size).getItems(),
                AsterixChannelRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), row.getCategory(), nz(row.getEdition()),
                        str(row.getPeriodMs()), nz(row.getTransmissionMode()), nz(row.getDestinationIp()),
                        str(row.getDestinationPort()), str(row.getTtl()), str(row.getMaximumDatagramBytes()),
                        String.valueOf(row.getChannelEnabled()), packSiteCodes(row)),
                (fields, existing) -> {
                    Map<String, String> siteIndex = siteCodeIndex();
                    AsterixChannelRow row = new AsterixChannelRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setCategory(req(fields, "category", "类别"));
                    row.setEdition(fields.get("edition"));
                    row.setPeriodMs(intg(fields, "periodMs", "发送周期", false));
                    row.setTransmissionMode(blankToNull(fields.get("transmissionMode")));
                    row.setDestinationIp(blankToNull(fields.get("destinationIp")));
                    row.setDestinationPort(intg(fields, "destinationPort", "目标端口", false));
                    row.setTtl(intg(fields, "ttl", "TTL", false));
                    row.setMaximumDatagramBytes(intg(fields, "maximumDatagramBytes", "最大报文", false));
                    row.setChannelEnabled(bool(fields, "channelEnabled", "通道启用", true));
                    row.setBoundSiteIds(parseSiteCodes(fields.get("boundSites"), siteIndex));
                    if (existing != null) {
                        AsterixChannelRow prior = (AsterixChannelRow) existing;
                        row.setRevision(prior.getRevision());
                        return radarService.updateChannel(prior.getId(), row);
                    }
                    return radarService.createChannel(row);
                }));
    }

    // ---- 打包/解包辅助 ----

    private String packRunways(AirportRow row) {
        if (row.getRunways() == null) {
            return "";
        }
        return row.getRunways().stream()
                .map(r -> joinWith(":", nz(r.getDesignation()), str(r.getLengthM()), str(r.getWidthM()),
                        str(r.getTrueHeadingDeg()), nz(r.getSurface()), str(r.getThr1Longitude()),
                        str(r.getThr1Latitude()), str(r.getThr2Longitude()), str(r.getThr2Latitude()),
                        str(r.getMagneticHeadingDeg()), nz(r.getRunwayStatus())))
                .collect(Collectors.joining(";"));
    }

    private List<RunwayRow> parseRunways(String packed) {
        List<RunwayRow> runways = new ArrayList<>();
        if (blankToNull(packed) == null) {
            return runways;
        }
        for (String item : packed.split(";")) {
            String[] parts = item.split(":", -1);
            if (parts.length < 1 || parts[0].trim().isEmpty()) {
                continue;
            }
            RunwayRow runway = new RunwayRow();
            runway.setDesignation(parts[0].trim());
            runway.setLengthM(parts.length > 1 ? parseIntOrNull(parts[1]) : null);
            runway.setWidthM(parts.length > 2 ? parseIntOrNull(parts[2]) : null);
            runway.setTrueHeadingDeg(parts.length > 3 ? parseDoubleOrNull(parts[3]) : null);
            runway.setSurface(parts.length > 4 ? parts[4].trim() : null);
            runway.setThr1Longitude(parts.length > 5 ? parseDoubleOrNull(parts[5]) : null);
            runway.setThr1Latitude(parts.length > 6 ? parseDoubleOrNull(parts[6]) : null);
            runway.setThr2Longitude(parts.length > 7 ? parseDoubleOrNull(parts[7]) : null);
            runway.setThr2Latitude(parts.length > 8 ? parseDoubleOrNull(parts[8]) : null);
            runway.setMagneticHeadingDeg(parts.length > 9 ? parseDoubleOrNull(parts[9]) : null);
            runway.setRunwayStatus(parts.length > 10 && !parts[10].trim().isEmpty()
                    ? parts[10].trim().toUpperCase(java.util.Locale.ROOT) : "ACTIVE");
            runways.add(runway);
        }
        return runways;
    }

    private String packRoutePath(AirwayRow row) {
        if (row.getSegments() == null || row.getSegments().isEmpty()) return "";
        List<String> codes = new ArrayList<>();
        codes.add(nz(row.getSegments().get(0).getStartPointCode()));
        for (AirwaySegmentRow segment : row.getSegments()) codes.add(nz(segment.getEndPointCode()));
        return String.join(" ", codes);
    }

    private List<AirwaySegmentRow> parseRoutePath(String packed, Map<String, String> navIndex) {
        List<AirwaySegmentRow> segments = new ArrayList<>();
        if (blankToNull(packed) == null) {
            return segments;
        }
        String[] codes = packed.trim().split("\\s+");
        if (codes.length < 2) {
            throw org.bluesky.dataprep.common.ApiException.badRequest("航路至少需要两个以空格分隔的导航点");
        }
        for (int i = 0; i < codes.length - 1; i++) {
            String startId = navIndex.get(codes[i].toUpperCase(java.util.Locale.ROOT));
            String endId = navIndex.get(codes[i + 1].toUpperCase(java.util.Locale.ROOT));
            if (startId == null || endId == null) {
                throw org.bluesky.dataprep.common.ApiException.badRequest(
                        "航路引用的导航点不存在：" + (startId == null ? codes[i] : codes[i + 1]));
            }
            AirwaySegmentRow segment = new AirwaySegmentRow();
            segment.setStartPointId(startId);
            segment.setEndPointId(endId);
            segments.add(segment);
        }
        return segments;
    }

    private String packPoints(WindFieldRow row) {
        if (row.getPoints() == null) {
            return "";
        }
        return row.getPoints().stream()
                .map(p -> joinWith(":", str(p.getLongitude()), str(p.getLatitude()), str(p.getAltitudeM()),
                        str(p.getWindDirectionDeg()), str(p.getWindSpeedMs())))
                .collect(Collectors.joining(";"));
    }

    private List<WindPointRow> parsePoints(String packed) {
        List<WindPointRow> points = new ArrayList<>();
        if (blankToNull(packed) == null) {
            return points;
        }
        for (String item : packed.split(";")) {
            String[] parts = item.split(":");
            if (parts.length != 5) {
                throw org.bluesky.dataprep.common.ApiException.badRequest(
                        "风场点格式应为 经度:纬度:高度:风向:风速：" + item);
            }
            WindPointRow point = new WindPointRow();
            point.setLongitude(parseDoubleOrNull(parts[0]));
            point.setLatitude(parseDoubleOrNull(parts[1]));
            point.setAltitudeM(parseIntOrNull(parts[2]));
            point.setWindDirectionDeg(parseDoubleOrNull(parts[3]));
            point.setWindSpeedMs(parseDoubleOrNull(parts[4]));
            points.add(point);
        }
        return points;
    }

    private String packSiteCodes(AsterixChannelRow row) {
        if (row.getBoundSiteIds() == null || row.getBoundSiteIds().isEmpty()) {
            return "";
        }
        Map<String, String> idToCode = new HashMap<>();
        for (RadarSiteRow site : allRadarSites()) {
            idToCode.put(site.getId(), site.getCode());
        }
        return row.getBoundSiteIds().stream()
                .map(id -> idToCode.getOrDefault(id, ""))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.joining(";"));
    }

    private List<String> parseSiteCodes(String packed, Map<String, String> siteIndex) {
        List<String> ids = new ArrayList<>();
        if (blankToNull(packed) == null) {
            return ids;
        }
        for (String code : packed.split(";")) {
            String id = siteIndex.get(code.trim().toUpperCase(java.util.Locale.ROOT));
            if (id == null) {
                throw org.bluesky.dataprep.common.ApiException.badRequest("绑定的雷达站编码不存在：" + code);
            }
            ids.add(id);
        }
        return ids;
    }

    private Map<String, String> navCodeIndex() {
        Map<String, String> index = new HashMap<>();
        for (NavPointRow point : allNavigationPoints()) {
            index.put(point.getCode().toUpperCase(java.util.Locale.ROOT), point.getId());
        }
        return index;
    }

    private Map<String, String> siteCodeIndex() {
        Map<String, String> index = new HashMap<>();
        for (RadarSiteRow site : allRadarSites()) {
            index.put(site.getCode().toUpperCase(java.util.Locale.ROOT), site.getId());
        }
        return index;
    }

    private void registerWeatherArea() {
        List<ExcelColumn> cols = Arrays.asList(
                col("name", "名称*"),
                col("weatherType", "类型*(WIND_SHEAR/MICROBURST/JET_STREAM/TURBULENCE/ADVECTION_FOG/RADIATION_FOG/THUNDERSTORM)"),
                col("area", "区域*(DMS坐标空格点串)"), col("lowerLimit", "下限*(S四位高度)"),
                col("upperLimit", "上限*(S四位高度)"));
        schemas.put("weather", new EntitySchema<>(
                "weather", "气象数据", cols,
                Arrays.asList("浦东风切变", "WIND_SHEAR",
                        "311200N1211800E 312400N1213600E 310600N1214800E", "S0000", "S3000"),
                (page, size) -> weatherAreaService.list(page, size).getItems(),
                row -> weatherExcelKey(row.getName(), row.getWeatherType()),
                row -> Arrays.asList(row.getName(), row.getWeatherType(), nz(row.getArea()),
                        row.getLowerLimit(), row.getUpperLimit()),
                (fields, existing) -> {
                    WeatherAreaRow row = new WeatherAreaRow();
                    row.setName(req(fields, "name", "名称"));
                    row.setWeatherType(req(fields, "weatherType", "类型"));
                    row.setArea(req(fields, "area", "区域"));
                    row.setLowerLimit(req(fields, "lowerLimit", "下限"));
                    row.setUpperLimit(req(fields, "upperLimit", "上限"));
                    if (existing != null) {
                        WeatherAreaRow prior = (WeatherAreaRow) existing;
                        row.setRevision(prior.getRevision());
                        return weatherAreaService.update(prior.getId(), row);
                    }
                    return weatherAreaService.create(row);
                }));
    }

    private static void copyPerformance(PerformanceRow source, PerformanceRow target) {
        target.aircraftId=source.aircraftId; target.status=source.status; target.performanceCategory=source.performanceCategory;
        target.holdingSpeedLow=source.holdingSpeedLow; target.holdingSpeedMiddle=source.holdingSpeedMiddle;
        target.holdingSpeedHigh=source.holdingSpeedHigh; target.takeoffSpeed=source.takeoffSpeed;
        target.takeoffDurationS=source.takeoffDurationS; target.takeoffAltitudeFt=source.takeoffAltitudeFt;
        target.takeoffDistanceNm=source.takeoffDistanceNm; target.landingSpeed=source.landingSpeed;
        target.radarCrossSection=source.radarCrossSection; target.maximumSpeed=source.maximumSpeed;
        target.maximumAltitudeLayer=source.maximumAltitudeLayer; target.maximumTurn=source.maximumTurn;
        target.machCapable=source.machCapable; target.jetAircraft=source.jetAircraft; target.standardTurn=source.standardTurn;
        target.turnResponse1=source.turnResponse1; target.turnResponse2=source.turnResponse2; target.turnResponse3=source.turnResponse3;
        target.accelerationResponse1=source.accelerationResponse1; target.accelerationResponse2=source.accelerationResponse2;
        target.accelerationResponse3=source.accelerationResponse3; target.decelerationResponse1=source.decelerationResponse1;
        target.decelerationResponse2=source.decelerationResponse2; target.decelerationResponse3=source.decelerationResponse3;
        target.climbResponse1=source.climbResponse1; target.climbResponse2=source.climbResponse2; target.climbResponse3=source.climbResponse3;
        target.descentResponse1=source.descentResponse1; target.descentResponse2=source.descentResponse2;
        target.descentResponse3=source.descentResponse3; target.accelerationKtsMin=source.accelerationKtsMin;
        target.decelerationKtsMin=source.decelerationKtsMin; target.stallSpeed=source.stallSpeed;
        target.climbSpeed=source.climbSpeed; target.descentSpeed=source.descentSpeed;
    }

    private static String performanceExcelKey(String code, String icao, String reacat, String altitude) {
        return (nz(code) + "/" + nz(icao) + "/" + nz(reacat) + "/" + nz(altitude))
                .trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String weatherExcelKey(String name, String weatherType) {
        return (nz(name) + "/" + nz(weatherType)).trim().toUpperCase(java.util.Locale.ROOT);
    }

    private List<NavPointRow> allNavigationPoints() {
        List<NavPointRow> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            List<NavPointRow> batch = navPointService.list(page++, 200).getItems();
            rows.addAll(batch);
            if (batch.size() < 200) {
                return rows;
            }
        }
    }

    private List<RadarSiteRow> allRadarSites() {
        List<RadarSiteRow> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            List<RadarSiteRow> batch = radarService.listSites(page++, 200).getItems();
            rows.addAll(batch);
            if (batch.size() < 200) {
                return rows;
            }
        }
    }

    // ---- 值转换辅助 ----

    static String joinWith(String sep, String... values) {
        return String.join(sep, values);
    }

    static String nz(String value) {
        return value == null ? "" : value;
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String time(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    static String req(Map<String, String> fields, String key, String label) {
        String value = blankToNull(fields.get(key));
        if (value == null) {
            throw org.bluesky.dataprep.common.ApiException.badRequest(label + "必填");
        }
        return value;
    }

    static Double dbl(Map<String, String> fields, String key, String label, boolean required) {
        String value = blankToNull(fields.get(key));
        if (value == null) {
            if (required) {
                throw org.bluesky.dataprep.common.ApiException.badRequest(label + "必填");
            }
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (NumberFormatException ex) {
            throw org.bluesky.dataprep.common.ApiException.badRequest(label + "必须是数字：" + value);
        }
    }

    static Integer intg(Map<String, String> fields, String key, String label, boolean required) {
        Double value = dbl(fields, key, label, required);
        if (value == null) return null;
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw org.bluesky.dataprep.common.ApiException.badRequest(label + "必须是整数：" + value);
        }
        return value.intValue();
    }

    static Boolean bool(Map<String, String> fields, String key, String label, boolean defaultValue) {
        String value = blankToNull(fields.get(key));
        if (value == null) return defaultValue;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        throw org.bluesky.dataprep.common.ApiException.badRequest(label + "仅允许 true / false：" + value);
    }

    static LocalDateTime dateTime(String value, String label) {
        String text = blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ex) {
            throw org.bluesky.dataprep.common.ApiException.badRequest(
                    label + "时间格式应为 yyyy-MM-ddTHH:mm:ss：" + value);
        }
    }

    static Double parseDoubleOrNull(String text) {
        try {
            if (text == null || text.trim().isEmpty()) return null;
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value)) throw new NumberFormatException("non-finite");
            return value;
        } catch (NumberFormatException ex) {
            throw org.bluesky.dataprep.common.ApiException.badRequest("数字格式错误：" + text);
        }
    }

    static Integer parseIntOrNull(String text) {
        Double value = parseDoubleOrNull(text);
        if (value == null) return null;
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw org.bluesky.dataprep.common.ApiException.badRequest("必须是整数：" + text);
        }
        return value.intValue();
    }
}
