package org.bluesky.dataprep.weather;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WindFieldService {

    static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "GLOBAL_CONSTANT", "TWO_DIMENSIONAL", "THREE_DIMENSIONAL"));

    private final WindFieldMapper mapper;
    private final RevisionService revisionService;

    public WindFieldService(WindFieldMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<WindFieldRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return new PageResult<>(
                mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public WindFieldRow get(String id) {
        WindFieldRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("风场不存在：" + id);
        }
        row.setPoints(mapper.findPoints(id));
        return row;
    }

    @Transactional
    public WindFieldRow create(WindFieldRow row) {
        validate(row);
        Guards.requireCodeUnique(mapper.countByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        defaults(row);
        mapper.insert(row);
        replacePoints(row.getId(), row.getPoints());
        revisionService.increment();
        return get(row.getId());
    }

    @Transactional
    public WindFieldRow update(String id, WindFieldRow body) {
        WindFieldRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        validate(body);
        Guards.requireCodeUnique(mapper.countByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        Guards.requireUpdated(mapper.update(body), id);
        if (body.getPoints() != null) {
            replacePoints(id, body.getPoints());
        }
        revisionService.increment();
        return get(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        WindFieldRow current = mapper.findById(id);
        if (current == null) {
            throw ApiException.notFound("风场不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        mapper.markPointsDeleted(id);
        revisionService.increment();
    }

    @Transactional
    public WindFieldRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        WindFieldRow current = mapper.findById(id);
        if (current == null) {
            throw ApiException.notFound("风场不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateStatus(id, revision, status), id);
        revisionService.increment();
        return get(id);
    }

    private void replacePoints(String windFieldId, List<WindPointRow> points) {
        mapper.markPointsDeleted(windFieldId);
        if (points == null) {
            return;
        }
        int order = 0;
        for (WindPointRow point : points) {
            List<String> missing = new ArrayList<>();
            if (point.getLongitude() == null) {
                missing.add("经度");
            }
            if (point.getLatitude() == null) {
                missing.add("纬度");
            }
            if (point.getAltitudeM() == null) {
                missing.add("高度");
            }
            if (point.getWindDirectionDeg() == null) {
                missing.add("风向");
            }
            if (point.getWindSpeedMs() == null) {
                missing.add("风速");
            }
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("风场点缺少必填项：" + String.join("、", missing));
            }
            point.setId(UUID.randomUUID().toString());
            point.setWindFieldId(windFieldId);
            point.setOrderNo(order++);
            mapper.insertPoint(point);
        }
    }

    private void defaults(WindFieldRow row) {
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
    }

    private void validate(WindFieldRow row) {
        if (row.getWindFieldType() != null && !TYPES.contains(row.getWindFieldType())) {
            throw ApiException.badRequest("风场类型不合法：" + row.getWindFieldType());
        }
        if ("GLOBAL_CONSTANT".equals(row.getWindFieldType())
                && (row.getWindDirectionDeg() == null || row.getWindSpeedMs() == null)) {
            throw ApiException.badRequest("恒定风必须提供风向与风速");
        }
    }
}
