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

/** 八类实体的 Excel 模式注册表：列定义 + 样例 + 导出行构建 + 导入应用。 */
@Component
public class EntitySchemas {

    private final NavPointService navPointService;
    private final AirportService airportService;
    private final AirspaceService airspaceService;
    private final AirwayService airwayService;
    private final WindFieldService windFieldService;
    private final PerformanceService performanceService;
    private final RadarService radarService;

    private final Map<String, EntitySchema<?>> schemas = new LinkedHashMap<>();

    public EntitySchemas(NavPointService navPointService, AirportService airportService,
                         AirspaceService airspaceService, AirwayService airwayService,
                         WindFieldService windFieldService, PerformanceService performanceService,
                         RadarService radarService) {
        this.navPointService = navPointService;
        this.airportService = airportService;
        this.airspaceService = airspaceService;
        this.airwayService = airwayService;
        this.windFieldService = windFieldService;
        this.performanceService = performanceService;
        this.radarService = radarService;
        registerNavPoint();
        registerAirport();
        registerAirspace();
        registerAirway();
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
                col("runways", "跑道(跑道号:长度:宽度:真方位:道面;…)"));
        schemas.put("airport", new EntitySchema<>(
                "airport", "机场", cols,
                Arrays.asList("ZBTJ", "天津滨海", "ZBTJ", "TSN", "CN", "4F", "117.35", "39.12", "3", "3600",
                        "16R/34L:3600:60:162:ASPHALT"),
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
                col("code", "编码*"), col("name", "名称*"), col("airwayDirection", "方向*(ONE_WAY/TWO_WAY)"),
                col("lowerValue", "下限值"), col("lowerReference", "下限基准"),
                col("upperValue", "上限值"), col("upperReference", "上限基准"),
                col("segments", "航段(起点编码-终点编码;…)"));
        schemas.put("airway", new EntitySchema<>(
                "airway", "航路", cols,
                Arrays.asList("T-800", "示例航路", "TWO_WAY", "6000", "MSL", "12000", "MSL", "PUD-SASAN;SASAN-AND"),
                (page, size) -> airwayService.list(page, size).getItems(),
                AirwayRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), row.getAirwayDirection(),
                        str(row.getLowerValue()), nz(row.getLowerReference()),
                        str(row.getUpperValue()), nz(row.getUpperReference()), packSegments(row)),
                (fields, existing) -> {
                    Map<String, String> navIndex = navCodeIndex();
                    AirwayRow row = new AirwayRow();
                    row.setCode(req(fields, "code", "编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setAirwayDirection(req(fields, "airwayDirection", "方向"));
                    row.setLowerValue(dbl(fields, "lowerValue", "下限值", false));
                    row.setLowerReference(fields.get("lowerReference"));
                    row.setUpperValue(dbl(fields, "upperValue", "上限值", false));
                    row.setUpperReference(fields.get("upperReference"));
                    row.setSegments(parseSegments(fields.get("segments"), navIndex));
                    if (existing != null) {
                        AirwayRow prior = (AirwayRow) existing;
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
                col("modelName", "型号"), col("performanceSource", "性能来源*(OPENAP/BADA/LEGACY/MANUAL)"),
                col("engineType", "发动机类型"), col("wakeTurbulenceCategory", "尾流等级(L/M/H/J)"),
                col("maximumTakeoffWeightKg", "最大起飞重量(千克)"), col("maximumAltitudeFt", "最大高度(英尺)"),
                col("maximumMach", "最大马赫"), col("defaultBankAngleDeg", "默认坡度(度)"));
        schemas.put("performance", new EntitySchema<>(
                "performance", "机型性能", cols,
                Arrays.asList("B738", "波音737-800", "BOEING", "737-800", "OPENAP", "CFM56", "M",
                        "79010", "41000", "0.82", "25"),
                (page, size) -> performanceService.list(page, size).getItems(),
                PerformanceRow::getCode,
                row -> Arrays.asList(
                        row.getCode(), row.getName(), nz(row.getManufacturer()), nz(row.getModelName()),
                        row.getPerformanceSource(), nz(row.getEngineType()), nz(row.getWakeTurbulenceCategory()),
                        str(row.getMaximumTakeoffWeightKg()), str(row.getMaximumAltitudeFt()),
                        str(row.getMaximumMach()), str(row.getDefaultBankAngleDeg())),
                (fields, existing) -> {
                    PerformanceRow row = new PerformanceRow();
                    row.setCode(req(fields, "code", "机型编码"));
                    row.setName(req(fields, "name", "名称"));
                    row.setManufacturer(fields.get("manufacturer"));
                    row.setModelName(fields.get("modelName"));
                    row.setPerformanceSource(req(fields, "performanceSource", "性能来源"));
                    row.setEngineType(fields.get("engineType"));
                    row.setWakeTurbulenceCategory(fields.get("wakeTurbulenceCategory"));
                    row.setMaximumTakeoffWeightKg(intg(fields, "maximumTakeoffWeightKg", "最大起飞重量", false));
                    row.setMaximumAltitudeFt(intg(fields, "maximumAltitudeFt", "最大高度", false));
                    row.setMaximumMach(dbl(fields, "maximumMach", "最大马赫", false));
                    row.setDefaultBankAngleDeg(dbl(fields, "defaultBankAngleDeg", "默认坡度", false));
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
                    row.setChannelEnabled(fields.get("channelEnabled") == null
                            || fields.get("channelEnabled").isEmpty()
                            || "true".equalsIgnoreCase(fields.get("channelEnabled")));
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
                        str(r.getTrueHeadingDeg()), nz(r.getSurface())))
                .collect(Collectors.joining(";"));
    }

    private List<RunwayRow> parseRunways(String packed) {
        List<RunwayRow> runways = new ArrayList<>();
        if (blankToNull(packed) == null) {
            return runways;
        }
        for (String item : packed.split(";")) {
            String[] parts = item.split(":");
            if (parts.length < 1 || parts[0].trim().isEmpty()) {
                continue;
            }
            RunwayRow runway = new RunwayRow();
            runway.setDesignation(parts[0].trim());
            runway.setLengthM(parts.length > 1 ? parseIntOrNull(parts[1]) : null);
            runway.setWidthM(parts.length > 2 ? parseIntOrNull(parts[2]) : null);
            runway.setTrueHeadingDeg(parts.length > 3 ? parseDoubleOrNull(parts[3]) : null);
            runway.setSurface(parts.length > 4 ? parts[4].trim() : null);
            runways.add(runway);
        }
        return runways;
    }

    private String packSegments(AirwayRow row) {
        if (row.getSegments() == null) {
            return "";
        }
        return row.getSegments().stream()
                .map(s -> nz(s.getStartPointCode()) + "-" + nz(s.getEndPointCode()))
                .collect(Collectors.joining(";"));
    }

    private List<AirwaySegmentRow> parseSegments(String packed, Map<String, String> navIndex) {
        List<AirwaySegmentRow> segments = new ArrayList<>();
        if (blankToNull(packed) == null) {
            return segments;
        }
        for (String item : packed.split(";")) {
            String[] pair = item.split("-");
            if (pair.length != 2) {
                throw org.bluesky.dataprep.common.ApiException.badRequest("航段格式应为 起点编码-终点编码：" + item);
            }
            String startId = navIndex.get(pair[0].trim());
            String endId = navIndex.get(pair[1].trim());
            if (startId == null || endId == null) {
                throw org.bluesky.dataprep.common.ApiException.badRequest(
                        "航段引用的导航点不存在：" + item);
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
            String id = siteIndex.get(code.trim());
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
            index.put(point.getCode(), point.getId());
        }
        return index;
    }

    private Map<String, String> siteCodeIndex() {
        Map<String, String> index = new HashMap<>();
        for (RadarSiteRow site : allRadarSites()) {
            index.put(site.getCode(), site.getId());
        }
        return index;
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
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw org.bluesky.dataprep.common.ApiException.badRequest(label + "必须是数字：" + value);
        }
    }

    static Integer intg(Map<String, String> fields, String key, String label, boolean required) {
        Double value = dbl(fields, key, label, required);
        return value == null ? null : (int) Math.round(value);
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
            return text == null || text.trim().isEmpty() ? null : Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            throw org.bluesky.dataprep.common.ApiException.badRequest("数字格式错误：" + text);
        }
    }

    static Integer parseIntOrNull(String text) {
        Double value = parseDoubleOrNull(text);
        return value == null ? null : (int) Math.round(value);
    }
}
