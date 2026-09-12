package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * P14 能力槽门控（详细设计 2.2 §7.8 规则 2）：ID/DECOMP 没有启用且 PUBLISHED
 * 的 profile 时返回 422 FEATURE_PROFILE_NOT_CONFIGURED，不得创建指令、不得下发
 * Adapter；ID 绝不回退为 IDENT。
 */
@Service
public class SpecialCapabilityProfileService {

    private final JdbcTemplate jdbc;

    public SpecialCapabilityProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requirePublishedProfile(String operationType, String modeCode) {
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM special_operation_profile "
                        + "WHERE operation_type = ? AND mode_code = ? "
                        + "AND status = 'PUBLISHED' AND enabled = 1",
                Integer.class, operationType, modeCode);
        if (published == null || published == 0) {
            throw new V2DomainException("FEATURE_PROFILE_NOT_CONFIGURED", 422,
                    "缺少启用且已发布的能力槽 profile: " + operationType + " " + modeCode,
                    Collections.singletonList("operationType"));
        }
        // No native engine implementation is registered for these profile actions yet.
        // A published configuration alone must not advertise a runnable capability.
        throw new V2DomainException("FEATURE_NOT_SUPPORTED",422,
                "当前原生引擎未提供 "+operationType+" profile 执行动作，不能创建该指令",
                Collections.singletonList("operationType"));
    }
}
