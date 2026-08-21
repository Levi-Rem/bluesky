package org.bluesky.dataprep.airway;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.bluesky.dataprep.common.ApiException;
import org.bluesky.dataprep.common.Guards;
import org.bluesky.dataprep.common.PageResult;
import org.bluesky.dataprep.common.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AirwayService {

    private final AirwayMapper mapper;
    private final RevisionService revisionService;
    private final NavPointRefMapper navPointRefMapper;

    public AirwayService(AirwayMapper mapper, RevisionService revisionService,
                         NavPointRefMapper navPointRefMapper) {
        this.mapper = mapper;
        this.revisionService = revisionService;
        this.navPointRefMapper = navPointRefMapper;
    }

    public PageResult<AirwayRow> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        List<AirwayRow> rows = mapper.selectPage(safePage * safeSize, safeSize);
        for (AirwayRow row : rows) {
            row.setSegments(mapper.findSegments(row.getId()));
        }
        return new PageResult<>(rows, safePage, safeSize, mapper.count());
    }

    public AirwayRow get(String id) {
        AirwayRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("航路不存在：" + id);
        }
        row.setSegments(mapper.findSegments(id));
        return row;
    }

    @Transactional
    public AirwayRow create(AirwayRow row) {
        validate(row);
        Guards.requireCodeUnique(mapper.countByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        defaults(row);
        mapper.insert(row);
        replaceSegments(row.getId(), row.getSegments());
        revisionService.increment();
        return get(row.getId());
    }

    @Transactional
    public AirwayRow update(String id, AirwayRow body) {
        AirwayRow current = get(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        validate(body);
        Guards.requireCodeUnique(mapper.countByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        Guards.requireUpdated(mapper.update(body), id);
        if (body.getSegments() != null) {
            replaceSegments(id, body.getSegments());
        }
        revisionService.increment();
        return get(id);
    }

    @Transactional
    public void delete(String id, int revision) {
        AirwayRow current = mapper.findById(id);
        if (current == null) {
            throw ApiException.notFound("航路不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markDeleted(id, revision), id);
        mapper.markSegmentsDeleted(id);
        revisionService.increment();
    }

    @Transactional
    public AirwayRow changeStatus(String id, String status, int revision) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
        AirwayRow current = mapper.findById(id);
        if (current == null) {
            throw ApiException.notFound("航路不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateStatus(id, revision, status), id);
        revisionService.increment();
        return get(id);
    }

    private void replaceSegments(String airwayId, List<AirwaySegmentRow> segments) {
        mapper.markSegmentsDeleted(airwayId);
        if (segments == null) {
            return;
        }
        Set<String> knownIds = new HashSet<>(navPointRefMapper.selectActiveIds());
        int order = 0;
        for (AirwaySegmentRow segment : segments) {
            if (segment.getStartPointId() == null || segment.getEndPointId() == null) {
                throw ApiException.badRequest("航段起止点必填");
            }
            if (!knownIds.contains(segment.getStartPointId())) {
                throw ApiException.badRequest("起点导航点不存在或已删除：" + segment.getStartPointId());
            }
            if (!knownIds.contains(segment.getEndPointId())) {
                throw ApiException.badRequest("终点导航点不存在或已删除：" + segment.getEndPointId());
            }
            segment.setId(UUID.randomUUID().toString());
            segment.setAirwayId(airwayId);
            segment.setOrderNo(order++);
            mapper.insertSegment(segment);
        }
    }

    private void defaults(AirwayRow row) {
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
    }

    private void validate(AirwayRow row) {
        if (row.getAirwayDirection() != null
                && !"ONE_WAY".equals(row.getAirwayDirection())
                && !"TWO_WAY".equals(row.getAirwayDirection())) {
            throw ApiException.badRequest("航路方向仅允许 ONE_WAY / TWO_WAY");
        }
    }

    /** 跨包只读引用：校验航段引用的导航点仍有效。 */
    @Mapper
    public interface NavPointRefMapper {
        @Select("SELECT id FROM navigation_point WHERE deleted = FALSE")
        List<String> selectActiveIds();
    }
}
