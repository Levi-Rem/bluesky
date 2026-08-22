package org.bluesky.dataprep.performance;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class PerformanceService {
    private final PerformanceMapper mapper;
    private final RevisionService revisionService;

    public PerformanceService(PerformanceMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<PerformanceRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return new PageResult<>(mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public PerformanceRow get(String id) {
        PerformanceRow row = mapper.findById(id);
        if (row == null) throw ApiException.notFound("机型性能不存在：" + id);
        return row;
    }

    @Transactional
    public PerformanceRow create(PerformanceRow row) {
        normalize(row);
        String aircraftId = mapper.findAircraftId(row);
        boolean newAircraft = aircraftId == null;
        if (newAircraft) {
            aircraftId = UUID.randomUUID().toString();
            row.aircraftId = aircraftId;
            mapper.insertAircraft(row);
        } else {
            row.aircraftId = aircraftId;
        }
        if (mapper.countLayer(aircraftId, row.altitudeLayer, "") > 0) {
            throw ApiException.badRequest("该机型的高度层已存在：" + row.code + " / " + row.altitudeLayer);
        }
        row.id = UUID.randomUUID().toString();
        row.sequenceNo = mapper.nextSequence(aircraftId);
        mapper.insertPerformance(row);
        if (!newAircraft) {
            mapper.updateAircraft(row);
            mapper.synchronizeCommonFields(row);
        }
        revisionService.increment();
        return mapper.findById(row.id);
    }

    @Transactional
    public PerformanceRow update(String id, PerformanceRow body) {
        PerformanceRow current = get(id);
        normalize(body);
        body.id = id;
        body.aircraftId = current.aircraftId;
        body.sequenceNo = current.sequenceNo;
        String identityOwner = mapper.findAircraftId(body);
        if (identityOwner != null && !identityOwner.equals(current.aircraftId)) {
            throw ApiException.badRequest("机型编码与尾流类别组合已存在：" + body.code);
        }
        if (mapper.countLayer(body.aircraftId, body.altitudeLayer, id) > 0) {
            throw ApiException.badRequest("该机型的高度层已存在：" + body.code + " / " + body.altitudeLayer);
        }
        Guards.requireUpdated(mapper.updateLayer(body), id);
        mapper.updateAircraft(body);
        mapper.synchronizeCommonFields(body);
        revisionService.increment();
        return mapper.findById(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        get(id);
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        revisionService.increment();
    }

    @Transactional
    public PerformanceRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        PerformanceRow row = get(id);
        if (row.revision != revision) throw ApiException.conflict("记录已被其他操作修改，请刷新后重试");
        row.status = status;
        mapper.updateAircraft(row);
        revisionService.increment();
        return mapper.findById(id);
    }

    private static void normalize(PerformanceRow row) {
        row.code = required(row.code, "机型编码").toUpperCase(Locale.ROOT);
        row.name = required(row.name, "名称");
        row.altitudeLayer = required(row.altitudeLayer, "高度层").toUpperCase(Locale.ROOT);
        row.icaoWakeCategory = upper(row.icaoWakeCategory);
        row.reacatWakeCategory = upper(row.reacatWakeCategory);
        row.performanceCategory = upper(row.performanceCategory);
        row.status = row.status == null || row.status.trim().isEmpty() ? "ENABLED" : row.status;
        row.createdBy = row.createdBy == null ? "local" : row.createdBy;
        row.updatedBy = row.updatedBy == null ? "local" : row.updatedBy;
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw ApiException.badRequest(label + "必填");
        return value.trim();
    }

    private static String upper(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
