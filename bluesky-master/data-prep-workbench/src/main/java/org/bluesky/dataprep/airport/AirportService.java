package org.bluesky.dataprep.airport;

import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AirportService {

    private final AirportMapper mapper;
    private final RevisionService revisionService;

    public AirportService(AirportMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    public PageResult<AirportRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return new PageResult<>(
                mapper.selectPage(safePage * safeSize, safeSize),
                safePage, safeSize, mapper.count());
    }

    public AirportRow get(String id) {
        AirportRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("机场不存在：" + id);
        }
        row.setRunways(mapper.findRunways(id));
        return row;
    }

    @Transactional
    public AirportRow create(AirportRow row) {
        validate(row);
        Guards.requireCodeUnique(mapper.countByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        normalizeDefaults(row);
        mapper.insert(row);
        replaceRunways(row.getId(), row.getRunways());
        revisionService.increment();
        return get(row.getId());
    }

    @Transactional
    public AirportRow update(String id, AirportRow body) {
        AirportRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        validate(body);
        Guards.requireCodeUnique(mapper.countByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        Guards.requireUpdated(mapper.update(body), id);
        if (body.getRunways() != null) {
            replaceRunways(id, body.getRunways());
        }
        revisionService.increment();
        return get(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        AirportRow current = mapper.findById(id);
        if (current == null) {
            throw ApiException.notFound("机场不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        mapper.markRunwaysDeleted(id);
        revisionService.increment();
    }

    @Transactional
    public AirportRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        AirportRow current = mapper.findById(id);
        if (current == null) {
            throw ApiException.notFound("机场不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateStatus(id, revision, status), id);
        revisionService.increment();
        return get(id);
    }

    private void replaceRunways(String airportId, java.util.List<RunwayRow> runways) {
        mapper.markRunwaysDeleted(airportId);
        if (runways == null) {
            return;
        }
        int order = 0;
        for (RunwayRow runway : runways) {
            if (runway.getDesignation() == null || runway.getDesignation().trim().isEmpty()) {
                throw ApiException.badRequest("跑道号必填");
            }
            runway.setId(UUID.randomUUID().toString());
            runway.setAirportId(airportId);
            runway.setOrderNo(order++);
            if (runway.getRunwayStatus() == null || runway.getRunwayStatus().isEmpty()) {
                runway.setRunwayStatus("ACTIVE");
            }
            mapper.insertRunway(runway);
        }
    }

    private void normalizeDefaults(AirportRow row) {
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
    }

    private void validate(AirportRow row) {
        if (row.getLongitude() != null && (row.getLongitude() < -180 || row.getLongitude() > 180)) {
            throw ApiException.badRequest("经度必须在 [-180, 180]");
        }
        if (row.getLatitude() != null && (row.getLatitude() < -90 || row.getLatitude() > 90)) {
            throw ApiException.badRequest("纬度必须在 [-90, 90]");
        }
    }
}
