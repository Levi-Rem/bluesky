package org.bluesky.dataprep.asf;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.RevisionService;
import org.bluesky.dataprep.performance.PerformanceMapper;
import org.bluesky.dataprep.performance.PerformanceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AircraftPerformanceAsfImportService {
    private final PerformanceMapper mapper;
    private final RevisionService revisionService;
    private final AircraftPerformanceAsfParser parser = new AircraftPerformanceAsfParser();

    public AircraftPerformanceAsfImportService(PerformanceMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    @Transactional
    public AircraftPerformanceImportResult replace(MultipartFile file) {
        requireFile(file);
        final AircraftPerformanceAsfParser.Result parsed;
        try {
            parsed = parser.parse(file.getInputStream());
        } catch (IOException | IllegalArgumentException ex) {
            throw ApiException.badRequest("飞机性能 ASF 文件解析失败：" + ex.getMessage());
        }

        mapper.deleteAllPerformance();
        mapper.deleteAllAircraft();
        Set<String> aircraftKeys = new HashSet<>();
        int rows = 0;
        for (AircraftPerformanceAsfParser.GroupData group : parsed.groups) {
            for (AircraftPerformanceAsfParser.Aircraft aircraft : group.aircraft) {
                String key = aircraft.code + "/" + aircraft.icaoWakeCategory + "/" + aircraft.reacatWakeCategory;
                if (!aircraftKeys.add(key)) {
                    parsed.warnings.add("重复机型定义已保留首条：" + key);
                    continue;
                }
                String aircraftId = stableId("aircraft", key);
                PerformanceRow type = baseRow(group, aircraft, aircraftId);
                mapper.insertAircraft(type);
                for (int layer = 0; layer < group.altitudeLayers.size(); layer++) {
                    PerformanceRow row = baseRow(group, aircraft, aircraftId);
                    row.id = stableId("performance", key + "/" + layer + "/" + group.altitudeLayers.get(layer));
                    row.sequenceNo = layer;
                    row.altitudeLayer = group.altitudeLayers.get(layer);
                    row.climbRateFtMin = intCurve(group, 0, layer);
                    row.descentRateFtMin = intCurve(group, 1, layer);
                    row.accelerationKtsMin = intCurve(group, 2, layer);
                    row.decelerationKtsMin = intCurve(group, 3, layer);
                    row.cruiseSpeed = textCurve(group, 4, layer);
                    row.stallSpeed = textCurve(group, 5, layer);
                    row.climbSpeed = textCurve(group, 6, layer);
                    row.descentSpeed = textCurve(group, 7, layer);
                    mapper.insertPerformance(row);
                    rows++;
                }
            }
        }
        revisionService.increment();

        AircraftPerformanceImportResult result = new AircraftPerformanceImportResult();
        result.setPerformanceGroupCount(parsed.groups.size());
        result.setAircraftTypeCount(aircraftKeys.size());
        result.setPerformanceRowCount(rows);
        result.setWarnings(parsed.warnings);
        return result;
    }

    private static PerformanceRow baseRow(AircraftPerformanceAsfParser.GroupData group,
                                          AircraftPerformanceAsfParser.Aircraft aircraft,
                                          String aircraftId) {
        PerformanceRow row = new PerformanceRow();
        row.aircraftId = aircraftId;
        row.code = aircraft.code;
        row.name = aircraft.code;
        row.icaoWakeCategory = aircraft.icaoWakeCategory;
        row.reacatWakeCategory = aircraft.reacatWakeCategory;
        row.performanceCategory = group.performanceCategory;
        row.status = "ENABLED";
        row.createdBy = "local";
        row.updatedBy = "local";
        row.holdingSpeedLow = group.holding.get(0);
        row.holdingSpeedMiddle = group.holding.get(1);
        row.holdingSpeedHigh = group.holding.get(2);
        row.takeoffSpeed = group.takeoffSpeed;
        row.takeoffDurationS = group.takeoffDurationS;
        row.takeoffAltitudeFt = group.takeoffAltitudeFt;
        row.takeoffDistanceNm = group.takeoffDistanceNm;
        row.landingSpeed = group.landingSpeed;
        row.radarCrossSection = group.radarCrossSection;
        row.maximumSpeed = group.maximumSpeed;
        row.maximumAltitudeLayer = group.maximumAltitudeLayer;
        row.maximumTurn = group.maximumTurn;
        row.machCapable = group.machCapable;
        row.jetAircraft = group.jetAircraft;
        row.standardTurn = group.standardTurn;
        applyResponse(row, group.responses.get(0), 0);
        applyResponse(row, group.responses.get(1), 1);
        applyResponse(row, group.responses.get(2), 2);
        applyResponse(row, group.responses.get(3), 3);
        applyResponse(row, group.responses.get(4), 4);
        return row;
    }

    private static void applyResponse(PerformanceRow row, List<Integer> values, int kind) {
        if (kind == 0) { row.turnResponse1=values.get(0); row.turnResponse2=values.get(1); row.turnResponse3=values.get(2); }
        if (kind == 1) { row.accelerationResponse1=values.get(0); row.accelerationResponse2=values.get(1); row.accelerationResponse3=values.get(2); }
        if (kind == 2) { row.decelerationResponse1=values.get(0); row.decelerationResponse2=values.get(1); row.decelerationResponse3=values.get(2); }
        if (kind == 3) { row.climbResponse1=values.get(0); row.climbResponse2=values.get(1); row.climbResponse3=values.get(2); }
        if (kind == 4) { row.descentResponse1=values.get(0); row.descentResponse2=values.get(1); row.descentResponse3=values.get(2); }
    }

    private static Integer intCurve(AircraftPerformanceAsfParser.GroupData group, int curve, int layer) {
        String value = textCurve(group, curve, layer);
        if (value == null) return null;
        String digits = value.replaceAll("[^0-9-]", "");
        return digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    private static String textCurve(AircraftPerformanceAsfParser.GroupData group, int curve, int layer) {
        List<String> values = group.curves.get(curve);
        return layer < values.size() ? values.get(layer) : null;
    }

    private static String stableId(String type, String key) {
        return UUID.nameUUIDFromBytes(("aircraft-performance:" + type + ":" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("飞机性能 ASF 文件不能为空");
        String name = file.getOriginalFilename();
        if (name != null && !name.toUpperCase().endsWith(".ASF")) {
            throw ApiException.badRequest("飞机性能文件必须为 .ASF 格式");
        }
    }
}
