package org.bluesky.dataprep.radar;

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
public class RadarService {

    static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList("CAT021", "CAT048", "CAT062"));

    private final RadarMapper mapper;
    private final RevisionService revisionService;

    public RadarService(RadarMapper mapper, RevisionService revisionService) {
        this.mapper = mapper;
        this.revisionService = revisionService;
    }

    // ---- 逻辑雷达站 ----

    public PageResult<RadarSiteRow> listSites(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = org.bluesky.dataprep.common.Paging.safePage(page, safeSize);
        List<RadarSiteRow> rows = mapper.selectSites(safePage * safeSize, safeSize);
        for (RadarSiteRow row : rows) {
            row.setBoundChannelCodes(mapper.selectBoundChannelCodes(row.getId()));
        }
        return new PageResult<>(rows, safePage, safeSize, mapper.countSites());
    }

    public List<RadarSiteRow> listAllSites() {
        List<RadarSiteRow> all = new ArrayList<>();
        int page = 0;
        while (true) {
            PageResult<RadarSiteRow> result = listSites(page++, 200);
            all.addAll(result.getItems());
            if (all.size() >= result.getTotal() || result.getItems().isEmpty()) return all;
        }
    }

    public RadarSiteRow getSite(String id) {
        RadarSiteRow row = mapper.findSiteById(id);
        if (row == null) {
            throw ApiException.notFound("逻辑雷达站不存在：" + id);
        }
        row.setBoundChannelCodes(mapper.selectBoundChannelCodes(id));
        return row;
    }

    @Transactional
    public RadarSiteRow createSite(RadarSiteRow row) {
        validateSite(row);
        Guards.requireCodeUnique(mapper.countSiteByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
        mapper.insertSite(row);
        revisionService.increment();
        return getSite(row.getId());
    }

    @Transactional
    public RadarSiteRow updateSite(String id, RadarSiteRow body) {
        RadarSiteRow current = getSite(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        validateSite(body);
        Guards.requireCodeUnique(mapper.countSiteByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        Guards.requireUpdated(mapper.updateSite(body), id);
        revisionService.increment();
        return getSite(id);
    }

    @Transactional
    public void deleteSite(String id, int revision) {
        RadarSiteRow current = mapper.findSiteById(id);
        if (current == null) {
            throw ApiException.notFound("逻辑雷达站不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "删除");
        if (mapper.countActiveCat048Bindings(id) > 0) {
            throw ApiException.conflict("逻辑雷达站正被 CAT048 通道绑定，不能删除：" + current.getCode());
        }
        Guards.requireUpdated(mapper.markSiteDeleted(id, revision), id);
        mapper.markSiteBindingsDeleted(id);
        revisionService.increment();
    }

    @Transactional
    public RadarSiteRow changeSiteStatus(String id, String status, int revision) {
        checkStatus(status);
        RadarSiteRow current = mapper.findSiteById(id);
        if (current == null) {
            throw ApiException.notFound("逻辑雷达站不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        if ("DISABLED".equals(status) && mapper.countActiveCat048Bindings(id) > 0) {
            throw ApiException.conflict("逻辑雷达站正被 CAT048 通道绑定，不能停用：" + current.getCode());
        }
        Guards.requireUpdated(mapper.updateSiteStatus(id, revision, status), id);
        revisionService.increment();
        return getSite(id);
    }

    // ---- ASTERIX 通道（含绑定） ----

    public PageResult<AsterixChannelRow> listChannels(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = org.bluesky.dataprep.common.Paging.safePage(page, safeSize);
        List<AsterixChannelRow> rows = mapper.selectChannels(safePage * safeSize, safeSize);
        for (AsterixChannelRow row : rows) {
            row.setBoundSiteIds(mapper.selectBoundSiteIds(row.getId()));
        }
        return new PageResult<>(rows, safePage, safeSize, mapper.countChannels());
    }

    public List<AsterixChannelRow> listAllChannels() {
        List<AsterixChannelRow> all = new ArrayList<>();
        int page = 0;
        while (true) {
            PageResult<AsterixChannelRow> result = listChannels(page++, 200);
            all.addAll(result.getItems());
            if (all.size() >= result.getTotal() || result.getItems().isEmpty()) return all;
        }
    }

    public AsterixChannelRow getChannel(String id) {
        AsterixChannelRow row = mapper.findChannelById(id);
        if (row == null) {
            throw ApiException.notFound("ASTERIX 通道不存在：" + id);
        }
        row.setBoundSiteIds(mapper.selectBoundSiteIds(id));
        return row;
    }

    @Transactional
    public AsterixChannelRow createChannel(AsterixChannelRow row) {
        validateChannel(row);
        Guards.requireCodeUnique(mapper.countChannelByCode(row.getCode(), "") > 0, row.getCode());
        row.setId(UUID.randomUUID().toString());
        normalizeChannel(row);
        mapper.insertChannel(row);
        replaceBindings(row.getId(), row.getBoundSiteIds());
        revisionService.increment();
        return getChannel(row.getId());
    }

    @Transactional
    public AsterixChannelRow updateChannel(String id, AsterixChannelRow body) {
        AsterixChannelRow current = getChannel(id);
        Guards.requireEditableSource(current.getSourceType(), "编辑");
        if (body.getBoundSiteIds() == null) body.setBoundSiteIds(current.getBoundSiteIds());
        if (body.getStatus() == null) body.setStatus(current.getStatus());
        if (body.getSourceReference() == null) body.setSourceReference(current.getSourceReference());
        validateChannel(body);
        Guards.requireCodeUnique(mapper.countChannelByCode(body.getCode(), id) > 0, body.getCode());
        body.setId(id);
        body.setSourceType(current.getSourceType());
        normalizeChannel(body);
        Guards.requireUpdated(mapper.updateChannel(body), id);
        if (body.getBoundSiteIds() != null) {
            replaceBindings(id, body.getBoundSiteIds());
        }
        revisionService.increment();
        return getChannel(id);
    }

    @Transactional
    public void deleteChannel(String id, int revision) {
        AsterixChannelRow current = mapper.findChannelById(id);
        if (current == null) {
            throw ApiException.notFound("ASTERIX 通道不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "删除");
        Guards.requireUpdated(mapper.markChannelDeleted(id, revision), id);
        mapper.markBindingsDeleted(id);
        revisionService.increment();
    }

    @Transactional
    public AsterixChannelRow changeChannelStatus(String id, String status, int revision) {
        checkStatus(status);
        AsterixChannelRow current = mapper.findChannelById(id);
        if (current == null) {
            throw ApiException.notFound("ASTERIX 通道不存在：" + id);
        }
        Guards.requireEditableSource(current.getSourceType(), "状态变更");
        Guards.requireUpdated(mapper.updateChannelStatus(id, revision, status), id);
        revisionService.increment();
        return getChannel(id);
    }

    /** CAT048 必须绑定至少一个启用的逻辑雷达站；CAT021/CAT062 可无站点。 */
    private void replaceBindings(String channelId, List<String> siteIds) {
        mapper.markBindingsDeleted(channelId);
        if (siteIds == null || siteIds.isEmpty()) {
            return;
        }
        int order = 0;
        List<String> seen = new ArrayList<>();
        for (String siteId : siteIds) {
            if (seen.contains(siteId)) {
                continue;
            }
            RadarSiteRow site = mapper.findSiteById(siteId);
            if (site == null) {
                throw ApiException.badRequest("绑定的逻辑雷达站不存在或已删除：" + siteId);
            }
            if (!"ENABLED".equals(site.getStatus())) {
                throw ApiException.badRequest("CAT048 仅能绑定启用的逻辑雷达站：" + site.getCode());
            }
            int displayOrder = order++;
            if (mapper.restoreBinding(siteId, channelId, displayOrder) == 0) {
                mapper.insertBinding(UUID.randomUUID().toString(), siteId, channelId, displayOrder);
            }
            seen.add(siteId);
        }
    }

    private void validateChannel(AsterixChannelRow row) {
        requireText(row.getCode(), "通道编码");
        requireText(row.getName(), "通道名称");
        checkStatus(row.getStatus());
        if (row.getCategory() == null || !CATEGORIES.contains(row.getCategory())) {
            throw ApiException.badRequest("通道类别仅允许 CAT021 / CAT048 / CAT062");
        }
        if ("CAT048".equals(row.getCategory())
                && (row.getBoundSiteIds() == null || row.getBoundSiteIds().isEmpty())) {
            throw ApiException.badRequest("CAT048 通道必须绑定至少一个逻辑雷达站");
        }
        if (row.getTransmissionMode() != null
                && !"UNICAST".equals(row.getTransmissionMode())
                && !"MULTICAST".equals(row.getTransmissionMode())) {
            throw ApiException.badRequest("传输方式仅允许 UNICAST / MULTICAST");
        }
        if (row.getDestinationPort() != null && (row.getDestinationPort() < 1 || row.getDestinationPort() > 65535)) {
            throw ApiException.badRequest("目标端口必须在 1–65535");
        }
        if (row.getTtl() != null && (row.getTtl() < 0 || row.getTtl() > 255)) {
            throw ApiException.badRequest("TTL 必须在 0–255");
        }
    }

    private void normalizeChannel(AsterixChannelRow row) {
        if (row.getStatus() == null || row.getStatus().isEmpty()) {
            row.setStatus("ENABLED");
        }
        if (row.getSourceType() == null || row.getSourceType().isEmpty()) {
            row.setSourceType("MANUAL");
        }
        if (row.getChannelEnabled() == null) {
            row.setChannelEnabled(Boolean.TRUE);
        }
        if (row.getConfigRevision() == null) {
            row.setConfigRevision(0);
        }
        if (row.getMaximumDatagramBytes() == null) {
            row.setMaximumDatagramBytes(1400);
        }
    }

    private void validateSite(RadarSiteRow row) {
        requireText(row.getCode(), "雷达站编码");
        requireText(row.getName(), "雷达站名称");
        checkStatus(row.getStatus());
        requireCoordinate(row.getLongitude(), row.getLatitude(), "逻辑雷达站");
        if (row.getSac() != null && (row.getSac() < 0 || row.getSac() > 255)) {
            throw ApiException.badRequest("SAC 必须在 0–255");
        }
        if (row.getSic() != null && (row.getSic() < 0 || row.getSic() > 255)) {
            throw ApiException.badRequest("SIC 必须在 0–255");
        }
        if (row.getSac() == null || row.getSic() == null) {
            throw ApiException.badRequest("SAC、SIC 必填");
        }
        if (row.getMaximumRangeNm() != null
                && (!Double.isFinite(row.getMaximumRangeNm()) || row.getMaximumRangeNm() <= 0)) {
            throw ApiException.badRequest("最大覆盖半径必须为正数");
        }
    }

    private void checkStatus(String status) {
        if (status == null || status.trim().isEmpty()) return;
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw ApiException.badRequest("状态仅允许 ENABLED / DISABLED");
        }
    }

    private static void requireCoordinate(Double longitude, Double latitude, String label) {
        if (longitude == null || latitude == null || !Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw ApiException.badRequest(label + "经纬度不合法");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.badRequest(label + "必填");
        }
    }
}
