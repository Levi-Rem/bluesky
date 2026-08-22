package org.bluesky.dataprep.physicalsector;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.bluesky.dataprep.nav.NavPointMapper;
import org.bluesky.dataprep.nav.NavPointRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PhysicalSectorService {
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList("SECTOR", "FIR"));
    private static final Set<String> MODES = new HashSet<>(Arrays.asList("NAV_POINT", "COORDINATE"));
    private static final Pattern DMS = Pattern.compile(
            "^(\\d{2})(\\d{2})(\\d{2})([NS])(\\d{3})(\\d{2})(\\d{2})([EW])$",
            Pattern.CASE_INSENSITIVE);

    private final PhysicalSectorMapper mapper;
    private final NavPointMapper navPointMapper;
    private final RevisionService revisionService;

    public PhysicalSectorService(PhysicalSectorMapper mapper, NavPointMapper navPointMapper,
                                 RevisionService revisionService) {
        this.mapper = mapper;
        this.navPointMapper = navPointMapper;
        this.revisionService = revisionService;
    }

    public PageResult<PhysicalSectorRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = org.bluesky.dataprep.common.Paging.safePage(page, safeSize);
        List<PhysicalSectorRow> rows = mapper.selectPage(safePage * safeSize, safeSize);
        for (PhysicalSectorRow row : rows) {
            row.setPoints(mapper.selectPoints(row.getId()));
        }
        return new PageResult<>(rows, safePage, safeSize, mapper.count());
    }

    public PhysicalSectorRow get(String id) {
        PhysicalSectorRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("物理扇区不存在：" + id);
        }
        row.setPoints(mapper.selectPoints(id));
        return row;
    }

    @Transactional
    public PhysicalSectorRow create(PhysicalSectorRow row) {
        prepareAndValidate(row);
        row.setId(UUID.randomUUID().toString());
        if (empty(row.getSourceType())) row.setSourceType("MANUAL");
        mapper.insert(row);
        replacePoints(row);
        revisionService.increment();
        return get(row.getId());
    }

    @Transactional
    public PhysicalSectorRow update(String id, PhysicalSectorRow body) {
        PhysicalSectorRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        prepareAndValidate(body);
        body.setId(id);
        body.setSourceType(current.getSourceType());
        if (body.getSourceSubtype() == null) body.setSourceSubtype(current.getSourceSubtype());
        if (body.getSourceFlag() == null) body.setSourceFlag(current.getSourceFlag());
        if (body.getSourceReference() == null) body.setSourceReference(current.getSourceReference());
        Guards.requireUpdated(mapper.update(body), id);
        mapper.markPointsDeleted(id);
        replacePoints(body);
        revisionService.increment();
        return get(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        PhysicalSectorRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        mapper.markPointsDeleted(id);
        revisionService.increment();
    }

    private void prepareAndValidate(PhysicalSectorRow row) {
        if ("GROUND".equalsIgnoreCase(row.getLowerLimit()) || "地面".equals(row.getLowerLimit())) {
            row.setLowerLimit("S0000");
        }
        if (!TYPES.contains(row.getSectorType())) {
            throw ApiException.badRequest("物理扇区类型仅允许 扇区 / FIR");
        }
        if (!MODES.contains(row.getCompositionMode())) {
            throw ApiException.badRequest("组成方式仅允许空域信息点或经纬度");
        }
        List<PhysicalSectorPointRow> points = row.getPoints() == null
                ? new ArrayList<PhysicalSectorPointRow>() : row.getPoints();
        if (points.size() > 1 && samePoint(points.get(0), points.get(points.size() - 1))) {
            points = new ArrayList<>(points.subList(0, points.size() - 1));
            row.setPoints(points);
        }
        if (points.size() < 3) {
            throw ApiException.badRequest("物理扇区组成至少需要三个边界点");
        }
        for (PhysicalSectorPointRow point : points) {
            if ("NAV_POINT".equals(row.getCompositionMode())) {
                prepareNavigationPoint(point);
            } else {
                prepareCoordinate(point);
            }
        }
    }

    private void prepareNavigationPoint(PhysicalSectorPointRow point) {
        if (empty(point.getNavPointId())) {
            throw ApiException.badRequest("空域信息点组成缺少点引用");
        }
        NavPointRow nav = navPointMapper.findById(point.getNavPointId());
        if (nav == null) {
            throw ApiException.badRequest("引用的空域信息点不存在：" + point.getNavPointId());
        }
        point.setPointName(nav.getName());
        point.setCoordinateText(empty(nav.getCoordinateText())
                ? nav.getLatitude() + "," + nav.getLongitude() : nav.getCoordinateText());
        point.setLatitude(nav.getLatitude());
        point.setLongitude(nav.getLongitude());
    }

    private void prepareCoordinate(PhysicalSectorPointRow point) {
        if (!empty(point.getCoordinateText())) {
            double[] coordinate = parseCoordinate(point.getCoordinateText());
            point.setLatitude(coordinate[0]);
            point.setLongitude(coordinate[1]);
        }
        if (point.getLatitude() == null || point.getLongitude() == null
                || !Double.isFinite(point.getLatitude()) || !Double.isFinite(point.getLongitude())
                || point.getLatitude() < -90 || point.getLatitude() > 90
                || point.getLongitude() < -180 || point.getLongitude() > 180) {
            throw ApiException.badRequest("物理扇区经纬度不合法");
        }
        if (empty(point.getCoordinateText())) {
            point.setCoordinateText(point.getLatitude() + "," + point.getLongitude());
        }
        point.setNavPointId(null);
    }

    private void replacePoints(PhysicalSectorRow row) {
        int order = 0;
        for (PhysicalSectorPointRow point : row.getPoints()) {
            point.setId(UUID.randomUUID().toString());
            point.setPhysicalSectorId(row.getId());
            point.setOrderNo(order++);
            mapper.insertPoint(point);
        }
    }

    private static boolean samePoint(PhysicalSectorPointRow left, PhysicalSectorPointRow right) {
        if (!empty(left.getNavPointId()) && left.getNavPointId().equals(right.getNavPointId())) return true;
        return !empty(left.getCoordinateText())
                && left.getCoordinateText().equalsIgnoreCase(right.getCoordinateText());
    }

    private static double[] parseCoordinate(String value) {
        Matcher dms = DMS.matcher(value.trim());
        if (dms.matches()) {
            int latMin = Integer.parseInt(dms.group(2));
            int latSec = Integer.parseInt(dms.group(3));
            int lonMin = Integer.parseInt(dms.group(6));
            int lonSec = Integer.parseInt(dms.group(7));
            if (latMin > 59 || latSec > 59 || lonMin > 59 || lonSec > 59) {
                throw ApiException.badRequest("坐标中的分、秒必须小于 60：" + value);
            }
            int latDeg = Integer.parseInt(dms.group(1));
            int lonDeg = Integer.parseInt(dms.group(5));
            if (latDeg > 90 || lonDeg > 180 || (latDeg == 90 && (latMin > 0 || latSec > 0))
                    || (lonDeg == 180 && (lonMin > 0 || lonSec > 0))) {
                throw ApiException.badRequest("坐标超出范围：" + value);
            }
            double lat = latDeg + latMin / 60d + latSec / 3600d;
            double lon = lonDeg + lonMin / 60d + lonSec / 3600d;
            if ("S".equalsIgnoreCase(dms.group(4))) lat = -lat;
            if ("W".equalsIgnoreCase(dms.group(8))) lon = -lon;
            return new double[]{lat, lon};
        }
        String[] decimal = value.trim().split("[,，]");
        if (decimal.length == 2) {
            try {
                return new double[]{Double.parseDouble(decimal[0].trim()), Double.parseDouble(decimal[1].trim())};
            } catch (NumberFormatException ignored) {
                // 统一由下方业务错误返回。
            }
        }
        throw ApiException.badRequest("坐标格式错误：" + value);
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
