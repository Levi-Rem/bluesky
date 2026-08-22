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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class PhysicalSectorAsfImportService {
    private final JdbcTemplate jdbc;
    private final RevisionService revisionService;
    private final FdpVolumesParser parser = new FdpVolumesParser();

    public PhysicalSectorAsfImportService(JdbcTemplate jdbc, RevisionService revisionService) {
        this.jdbc = jdbc;
        this.revisionService = revisionService;
    }

    @Transactional
    public PhysicalSectorImportResult replace(MultipartFile file) {
        requireFile(file);
        final FdpVolumesParser.Result parsed;
        try {
            parsed = parser.parse(file.getInputStream());
        } catch (IOException ex) {
            throw ApiException.badRequest("FDP 体积 ASF 文件读取失败：" + ex.getMessage());
        }

        List<Region> regions = new ArrayList<>();
        for (FdpVolumesParser.Sector sector : parsed.sectors) {
            regions.addAll(regionsOf(sector, "SECTOR", parsed));
        }
        for (FdpVolumesParser.Sector fir : parsed.firs) {
            regions.addAll(regionsOf(fir, "FIR", parsed));
        }

        List<Object[]> sectorRows = new ArrayList<>();
        List<Object[]> pointRows = new ArrayList<>();
        String sourceName = file.getOriginalFilename() == null ? "FDP_VOLUMES_DEFINITION.ASF" : file.getOriginalFilename();
        for (Region region : regions) {
            String key = region.type + ":" + region.name + ":" + region.sourceLine + ":"
                    + region.layerStart + "-" + region.layerEnd + ":" + String.join(" ", region.pointNames);
            String id = stableId("physical-sector", key);
            sectorRows.add(new Object[]{id, region.name, region.type, "COORDINATE", region.upperLimit,
                    region.lowerLimit, emptyToNull(region.sourceSubtype), emptyToNull(region.sourceFlag),
                    "ACCOPS_ASF", sourceName + ":" + region.sourceLine, 0, false, "asf-import", "asf-import"});
            int order = 0;
            for (String pointName : region.pointNames) {
                FdpVolumesParser.Point point = parsed.points.get(pointName);
                pointRows.add(new Object[]{stableId("physical-sector-point", key + ":" + order), id, order++,
                        null, pointName, point.coordinateText, point.longitude, point.latitude, false});
            }
        }

        jdbc.update("DELETE FROM physical_sector_point");
        jdbc.update("DELETE FROM physical_sector");
        jdbc.batchUpdate("INSERT INTO physical_sector (id, name, sector_type, composition_mode, upper_limit, "
                + "lower_limit, source_subtype, source_flag, source_type, source_reference, revision, deleted, "
                + "created_by, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", sectorRows);
        jdbc.batchUpdate("INSERT INTO physical_sector_point (id, physical_sector_id, order_no, nav_point_id, "
                + "point_name, coordinate_text, longitude, latitude, deleted) VALUES (?,?,?,?,?,?,?,?,?)", pointRows);
        revisionService.increment();

        PhysicalSectorImportResult result = new PhysicalSectorImportResult();
        result.setSourceSectorCount(parsed.sectors.size());
        result.setSourceFirCount(parsed.firs.size());
        result.setRegionCount(sectorRows.size());
        result.setBoundaryPointCount(pointRows.size());
        return result;
    }

    private static List<Region> regionsOf(FdpVolumesParser.Sector sector, String type,
                                           FdpVolumesParser.Result parsed) {
        Map<String, GeometryLevels> groups = new LinkedHashMap<>();
        for (String volumeName : sector.volumeNames) {
            FdpVolumesParser.Volume volume = parsed.volumes.get(volumeName);
            String geometry = String.join(" ", volume.pointNames);
            GeometryLevels group = groups.get(geometry);
            if (group == null) {
                group = new GeometryLevels(volume.pointNames);
                groups.put(geometry, group);
            }
            for (int layer = volume.layerStart; layer <= volume.layerEnd; layer++) group.levels.add(layer);
        }

        List<Region> result = new ArrayList<>();
        for (GeometryLevels group : groups.values()) {
            int start = -1;
            int previous = -1;
            for (Integer layer : group.levels) {
                if (start < 0) start = layer;
                if (previous >= 0 && layer != previous + 1) {
                    result.add(region(sector, type, group.pointNames, start, previous, parsed));
                    start = layer;
                }
                previous = layer;
            }
            if (start >= 0) result.add(region(sector, type, group.pointNames, start, previous, parsed));
        }
        return result;
    }

    private static Region region(FdpVolumesParser.Sector source, String type, List<String> points,
                                 int start, int end, FdpVolumesParser.Result parsed) {
        Region region = new Region();
        region.name = source.name;
        region.type = type;
        region.sourceSubtype = source.sourceSubtype;
        region.sourceFlag = source.sourceFlag;
        region.sourceLine = source.lineNumber;
        region.pointNames = new ArrayList<>(points);
        region.layerStart = start;
        region.layerEnd = end;
        region.lowerLimit = start == 1 ? "S0000" : parsed.layerUpperLimits.get(start - 1);
        region.upperLimit = parsed.layerUpperLimits.get(end);
        return region;
    }

    private static String stableId(String type, String key) {
        return UUID.nameUUIDFromBytes(("accops-asf:" + type + ":" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("FDP 体积定义 ASF 文件不能为空");
        String name = file.getOriginalFilename();
        if (name != null && !name.toUpperCase().endsWith(".ASF")) {
            throw ApiException.badRequest("FDP 体积定义文件必须为 .ASF 格式");
        }
    }

    static class GeometryLevels {
        final List<String> pointNames;
        final Set<Integer> levels = new TreeSet<>();
        GeometryLevels(List<String> pointNames) { this.pointNames = new ArrayList<>(pointNames); }
    }

    static class Region {
        String name;
        String type;
        String sourceSubtype;
        String sourceFlag;
        int sourceLine;
        int layerStart;
        int layerEnd;
        String lowerLimit;
        String upperLimit;
        List<String> pointNames;
    }
}
