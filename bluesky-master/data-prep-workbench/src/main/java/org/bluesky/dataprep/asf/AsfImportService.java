package org.bluesky.dataprep.asf;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将当前 ACCOPS 的 ASF 特征点、编码航路、SID 和 STAR 作为一个不可分割的数据集导入。 */
@Service
public class AsfImportService {

    private final JdbcTemplate jdbc;
    private final RevisionService revisionService;
    private final AsfParser parser = new AsfParser();

    public AsfImportService(JdbcTemplate jdbc, RevisionService revisionService) {
        this.jdbc = jdbc;
        this.revisionService = revisionService;
    }

    @Transactional
    public AsfImportResult replace(MultipartFile pointFile, MultipartFile routeFile) {
        requireFile(pointFile, "特征点");
        requireFile(routeFile, "航路");
        final AsfParser.PointResult pointResult;
        final List<AsfParser.Route> routes;
        try {
            pointResult = parser.parsePoints(pointFile.getInputStream());
            routes = parser.parseRoutes(routeFile.getInputStream());
        } catch (IOException ex) {
            throw ApiException.badRequest("ASF 文件读取失败：" + ex.getMessage());
        }

        validateReferences(pointResult, routes);
        Map<String, String> pointIds = new HashMap<>();
        List<Object[]> pointRows = new ArrayList<>();
        for (AsfParser.Point point : pointResult.getPoints().values()) {
            String id = stableId("nav", point.code);
            pointIds.put(point.code, id);
            pointRows.add(new Object[]{id, point.code, displayName(point), standardType(point.sourcePointType),
                    point.longitude, point.latitude, emptyToNull(point.comment), point.sourcePointType,
                    point.coordinateText, emptyToNull(point.relevantFlag), emptyToNull(point.applicableAirports),
                    emptyToNull(point.pilotFlag), emptyToNull(point.dtiFlag), emptyToNull(point.tfmFlag),
                    "ENABLED", "ACCOPS_ASF", source(pointFile, point.lineNumber), 0, false, "asf-import", "asf-import"});
        }

        List<Object[]> airwayRows = new ArrayList<>();
        List<Object[]> segmentRows = new ArrayList<>();
        int codedRouteCount = 0;
        int sidCount = 0;
        int starCount = 0;
        for (AsfParser.Route route : routes) {
            String airwayId = stableId("airway", route.code);
            airwayRows.add(new Object[]{airwayId, route.code, route.code, direction(route.sense),
                    emptyToNull(route.cruiseLevelRule), emptyToNull(route.rnavCapability),
                    emptyToNull(route.rnavCapabilityPost2012), emptyToNull(route.rnpCapabilityPost2012),
                    emptyToNull(route.rvsmLevel), route.routeType, emptyToNull(route.procedureAirport),
                    emptyToNull(route.procedureProfile), emptyToNull(route.procedureRunway),
                    emptyToNull(route.procedureDirection), emptyToNull(route.procedureOperation),
                    emptyToNull(route.eligibleRoute), "ENABLED", "ACCOPS_ASF", source(routeFile, route.lineNumber),
                    0, false, "asf-import", "asf-import"});
            if ("SID".equals(route.routeType)) sidCount++;
            else if ("STAR".equals(route.routeType)) starCount++;
            else codedRouteCount++;
            for (int i = 0; i < route.pointCodes.size() - 1; i++) {
                String start = route.pointCodes.get(i);
                String end = route.pointCodes.get(i + 1);
                segmentRows.add(new Object[]{stableId("segment", route.code + ":" + i), airwayId, i,
                        pointIds.get(start), pointIds.get(end), direction(route.sense), false});
            }
        }

        // 替换范围严格限定为导航点、航路及其航段；机场、空域边界等其他数据不受影响。
        jdbc.update("DELETE FROM airway_segment");
        jdbc.update("DELETE FROM airway");
        jdbc.update("DELETE FROM navigation_point");
        jdbc.batchUpdate("INSERT INTO navigation_point (id, code, name, point_type, longitude, latitude, "
                + "description, source_point_type, coordinate_text, relevant_flag, applicable_airports, "
                + "pilot_flag, dti_flag, tfm_flag, status, source_type, source_reference, revision, deleted, "
                + "created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", pointRows);
        jdbc.batchUpdate("INSERT INTO airway (id, code, name, airway_direction, cruise_level_rule, "
                + "rnav_capability, rnav_capability_post_2012, rnp_capability_post_2012, rvsm_level, "
                + "route_type, procedure_airport, procedure_profile, procedure_runway, procedure_direction, "
                + "procedure_operation, eligible_route, status, source_type, source_reference, revision, deleted, "
                + "created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", airwayRows);
        jdbc.batchUpdate("INSERT INTO airway_segment (id, airway_id, order_no, start_point_id, end_point_id, "
                + "segment_direction, deleted) VALUES (?,?,?,?,?,?,?)", segmentRows);
        revisionService.increment();

        AsfImportResult result = new AsfImportResult();
        result.setNavigationPointCount(pointRows.size());
        result.setAirwayCount(airwayRows.size());
        result.setAirwaySegmentCount(segmentRows.size());
        result.setCodedRouteCount(codedRouteCount);
        result.setSidCount(sidCount);
        result.setStarCount(starCount);
        result.setDuplicateDefinitionCount(pointResult.getConflicts().size());
        result.setDuplicateDefinitions(pointResult.getConflicts());
        return result;
    }

    private static void validateReferences(AsfParser.PointResult points, List<AsfParser.Route> routes) {
        List<String> missing = new ArrayList<>();
        for (AsfParser.Route route : routes) {
            for (String code : route.pointCodes) {
                if (!points.getPoints().containsKey(code)) {
                    missing.add(route.code + " → " + code);
                    if (missing.size() >= 20) {
                        break;
                    }
                }
            }
            if (missing.size() >= 20) {
                break;
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("航路引用了特征点文件中不存在的编码：" + String.join("，", missing));
        }
    }

    private static String standardType(String sourceType) {
        if ("REPORT".equals(sourceType)) return "FIX";
        if ("AIRPORT_I".equals(sourceType)) return "AIRPORT";
        if ("VORDME".equals(sourceType)) return "VOR_DME";
        if ("VOR".equals(sourceType) || "NDB".equals(sourceType)) return sourceType;
        return "OTHER";
    }

    private static String direction(String sense) {
        return "B".equalsIgnoreCase(sense) ? "TWO_WAY" : "ONE_WAY";
    }

    private static String displayName(AsfParser.Point point) {
        return point.comment == null || point.comment.trim().isEmpty() ? point.code : point.comment.trim();
    }

    private static String stableId(String type, String key) {
        return UUID.nameUUIDFromBytes(("accops-asf:" + type + ":" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String source(MultipartFile file, int line) {
        String name = file.getOriginalFilename() == null ? "unknown.asf" : file.getOriginalFilename();
        return name + ":" + line;
    }

    private static Object emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void requireFile(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest(label + " ASF 文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toUpperCase().endsWith(".ASF")) {
            throw ApiException.badRequest(label + "文件必须为 .ASF 格式");
        }
    }
}
