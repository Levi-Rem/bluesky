package org.bluesky.training.display;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.DisplayProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P18：屏幕方案与标牌布局（详细设计 2.2 §5.7/§8.7）。
 * 同终端最多 20 个命名方案、名称唯一、默认方案不可删除；
 * 调用方案不改变航空器选择、控制权、指令和训练状态。
 */
@Service
public class DisplayProfileService {

    public static final int MAX_PROFILES_PER_TERMINAL = 20;

    private final DisplayProfileMapper mapper;

    public DisplayProfileService(DisplayProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> create(String terminalId, String name, String contentJson) {
        requireValidName(name);
        requireContent(contentJson);
        if (mapper.countByTerminal(terminalId) >= MAX_PROFILES_PER_TERMINAL) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "同一终端最多保存 " + MAX_PROFILES_PER_TERMINAL + " 个命名方案",
                    Arrays.asList("name"));
        }
        String id = UUID.randomUUID().toString();
        try {
            mapper.insert(id, terminalId, name.trim(), contentJson);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 保存方案语义（设计 8.7）：同名方案覆盖内容并递增修订号
            Map<String, Object> existing = mapper.findByName(terminalId, name.trim());
            if (existing != null) {
                mapper.updateContent(String.valueOf(existing.get("id")), contentJson);
                return mapper.findById(String.valueOf(existing.get("id")));
            }
            throw duplicate;
        }
        return mapper.findById(id);
    }

    @Transactional
    public Map<String, Object> update(String profileId, String contentJson) {
        requireContent(contentJson);
        Map<String, Object> current = requireProfile(profileId);
        mapper.updateContent(profileId, contentJson);
        return mapper.findById(profileId);
    }

    @Transactional
    public Map<String, Object> update(String profileId, String contentJson, long revision) {
        requireContent(contentJson);
        if (mapper.updateContentAtRevision(profileId,contentJson,revision)!=1)
            throw new V2DomainException("REVISION_CONFLICT",409,"显示配置版本已改变",Arrays.asList("If-Match"));
        return mapper.findById(profileId);
    }

    /** 删除非默认方案；默认方案不可删除（详细设计 8.7）。 */
    @Transactional
    public void delete(String profileId) {
        Map<String, Object> profile = requireProfile(profileId);
        Object defaultValue=profile.get("is_default");
        if (Boolean.TRUE.equals(defaultValue) || (defaultValue instanceof Number && ((Number)defaultValue).intValue()==1)) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "默认方案不能删除", Arrays.asList("profileId"));
        }
        mapper.deleteById(profileId);
    }

    /** 首个方案自动成为默认；applyDefault 不改变任何业务状态。 */
    @Transactional
    public Map<String, Object> applyDefault(String terminalId) {
        Map<String, Object> profile = mapper.findDefault(terminalId);
        if (profile == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "终端尚无任何方案", Arrays.asList("terminalId"));
        }
        return profile;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String terminalId) {
        return mapper.listByTerminal(terminalId);
    }

    /**
     * 保存标牌布局：同终端同航空器合并为一条（uq_label_layout 唯一）。
     * MySQL 无 H2 的 MERGE INTO ... KEY 语法（评审 F12），故在同一事务内
     * 先 UPDATE、未命中再 INSERT，并发插入竞态由唯一键约束兜底。
     */
    @Transactional
    public void saveLabelLayout(String terminalId, String aircraftId,
                                double angleDeg, int distancePx, String mode, boolean pinned) {
        int updated = mapper.updateLabelLayout(terminalId, aircraftId,
                angleDeg, distancePx, mode, pinned);
        if (updated == 0) {
            try {
                mapper.insertLabelLayout(UUID.randomUUID().toString(), terminalId, aircraftId,
                        angleDeg, distancePx, mode, pinned);
            } catch (org.springframework.dao.DuplicateKeyException race) {
                // 并发下另一事务已插入：退回更新语义
                mapper.updateLabelLayout(terminalId, aircraftId,
                        angleDeg, distancePx, mode, pinned);
            }
        }
    }

    @Transactional
    public void deleteLabelLayout(String terminalId, String aircraftId) {
        mapper.deleteLabelLayout(terminalId, aircraftId);
    }

    private Map<String, Object> requireProfile(String profileId) {
        Map<String, Object> profile = profileId == null ? null : mapper.findById(profileId);
        if (profile == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "屏幕方案不存在: " + profileId, Arrays.asList("profileId"));
        }
        return profile;
    }

    private static void requireValidName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 64) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "方案名称必须 1–64 字符", Arrays.asList("name"));
        }
    }

    private static void requireContent(String contentJson) {
        if (contentJson == null || contentJson.trim().isEmpty()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "方案内容不能为空", Arrays.asList("contentJson"));
        }
    }
}
