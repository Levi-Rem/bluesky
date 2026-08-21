package org.bluesky.dataprep.performance;

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
public class PerformanceService {

    static final Set<String> SOURCES = new HashSet<>(Arrays.asList(
            "OPENAP", "BADA", "LEGACY", "MANUAL"));

    private final PerformanceMapper mapper;
    private final RevisionService revisionService;

    public PerformanceService(PerformanceMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<PerformanceRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return new PageResult<>(
                mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public PerformanceRow get(String id) {
        PerformanceRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("机型性能不存在：" + id);
        }
        return row;
    }

    @Transactional
    public PerformanceRow create(PerformanceRow row) {
        if (row.getPerformanceSource() == null || !SOURCES.contains(row.getPerformanceSource())) {
            throw ApiException.badRequest("性能来源不合法：" + row.getPerformanceSource());
        }
        Guards.requireCodeUnique(mapper.countByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        defaults(row);
        mapper.insert(row);
        revisionService.increment();
        return mapper.findById(row.getId());
    }

    @Transactional
    public PerformanceRow update(String id, PerformanceRow body) {
        PerformanceRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        if (body.getPerformanceSource() != null && !SOURCES.contains(body.getPerformanceSource())) {
            throw ApiException.badRequest("性能来源不合法：" + body.getPerformanceSource());
        }
        Guards.requireCodeUnique(mapper.countByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        Guards.requireUpdated(mapper.update(body), id);
        revisionService.increment();
        return mapper.findById(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        PerformanceRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        revisionService.increment();
    }

    @Transactional
    public PerformanceRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        PerformanceRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateStatus(id, revision, status), id);
        revisionService.increment();
        return mapper.findById(id);
    }

    private void defaults(PerformanceRow row) {
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL".equals(row.getPerformanceSource()) ? "MANUAL" : "MANUAL");
        }
    }
}
