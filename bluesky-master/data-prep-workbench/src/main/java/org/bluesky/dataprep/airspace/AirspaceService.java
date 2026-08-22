package org.bluesky.dataprep.airspace;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.GeoJsonValidator;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class AirspaceService {

    static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "FIR", "TMA", "CTR", "CTA", "RESTRICTED", "DANGER", "PROHIBITED"));

    private final AirspaceMapper mapper;
    private final RevisionService revisionService;

    public AirspaceService(AirspaceMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<AirspaceRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = org.bluesky.dataprep.common.Paging.safePage(page, safeSize);
        return new PageResult<>(
                mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public AirspaceRow get(String id) {
        AirspaceRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("空域不存在：" + id);
        }
        return row;
    }

    @Transactional
    public AirspaceRow create(AirspaceRow row) {
        validate(row);
        Guards.requireCodeUnique(mapper.countByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        defaults(row);
        mapper.insert(row);
        revisionService.increment();
        return mapper.findById(row.getId());
    }

    @Transactional
    public AirspaceRow update(String id, AirspaceRow body) {
        AirspaceRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        validate(body);
        Guards.requireCodeUnique(mapper.countByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        if (body.getStatus() == null) body.setStatus(current.getStatus());
        if (body.getSourceReference() == null) body.setSourceReference(current.getSourceReference());
        if (body.getValidFrom() == null) body.setValidFrom(current.getValidFrom());
        if (body.getValidTo() == null) body.setValidTo(current.getValidTo());
        Guards.requireUpdated(mapper.update(body), id);
        revisionService.increment();
        return mapper.findById(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        AirspaceRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        revisionService.increment();
    }

    @Transactional
    public AirspaceRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        AirspaceRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateStatus(id, revision, status), id);
        revisionService.increment();
        return mapper.findById(id);
    }

    private void defaults(AirspaceRow row) {
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
    }

    private void validate(AirspaceRow row) {
        if (row.getStatus() != null && !row.getStatus().isEmpty()
                && !"ENABLED".equals(row.getStatus()) && !"DISABLED".equals(row.getStatus())) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        if (row.getAirspaceType() != null && !TYPES.contains(row.getAirspaceType())) {
            throw ApiException.badRequest("空域类型不合法：" + row.getAirspaceType());
        }
        row.setBoundary(GeoJsonValidator.validatePolygonGeometry(row.getBoundary(), "空域边界"));
    }
}
