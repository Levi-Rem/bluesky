package org.bluesky.dataprep.nav;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class NavPointService {

    static final Set<String> POINT_TYPES = new HashSet<>(Arrays.asList(
            "FIX", "AIRPORT", "VOR", "NDB", "DME", "VOR_DME", "ILS", "OTHER"));

    private final NavPointMapper mapper;
    private final RevisionService revisionService;

    public NavPointService(NavPointMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<NavPointRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return new PageResult<>(
                mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public NavPointRow get(String id) {
        NavPointRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("导航点不存在：" + id);
        }
        return row;
    }

    @Transactional
    public NavPointRow create(NavPointRow row) {
        validate(row);
        Guards.requireCodeUnique(mapper.countByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
        mapper.insert(row);
        revisionService.increment();
        return mapper.findById(row.getId());
    }

    @Transactional
    public NavPointRow update(String id, NavPointRow body) {
        NavPointRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        validate(body);
        Guards.requireCodeUnique(mapper.countByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        // 保留客户端提交的 revision：乐观锁由 UPDATE ... WHERE revision = #{revision} 强制
        Guards.requireUpdated(mapper.update(body), id);
        revisionService.increment();
        return mapper.findById(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        NavPointRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "删除");
        if (mapper.countActiveAirwayReferences(id) > 0) {
            throw ApiException.conflict("导航点正被有效航路引用，不能删除：" + current.getCode());
        }
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        revisionService.increment();
    }

    @Transactional
    public NavPointRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        NavPointRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateStatus(id, revision, status, current.getUpdatedBy()), id);
        revisionService.increment();
        return mapper.findById(id);
    }

    private void validate(NavPointRow row) {
        if (row.getLongitude() != null && (row.getLongitude() < -180 || row.getLongitude() > 180)) {
            throw ApiException.badRequest("经度必须在 [-180, 180]");
        }
        if (row.getLatitude() != null && (row.getLatitude() < -90 || row.getLatitude() > 90)) {
            throw ApiException.badRequest("纬度必须在 [-90, 90]");
        }
        if (row.getPointType() != null && !POINT_TYPES.contains(row.getPointType())) {
            throw ApiException.badRequest("导航点类型不合法：" + row.getPointType());
        }
    }
}
