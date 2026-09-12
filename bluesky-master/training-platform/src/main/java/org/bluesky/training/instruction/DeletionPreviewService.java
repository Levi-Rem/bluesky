package org.bluesky.training.instruction;

import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.persistence.DeletionTokenMapper;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.bluesky.training.common.V2DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P15：删除预览与一次性确认 token（详细设计 2.2 §7.9）。
 * token 绑定 航空器 ID + revision + 受信终端 + requestId，30 秒过期、
 * 原子消费、摘要落库不保存明文；版本变化后重新预览。
 * 评审 P0-5：HMAC-SHA256 签名（无签名 token 可被任意客户端伪造）；
 * 摘要落库 + consumed_at 条件更新（同 token 不可重放）；预览响应不回摘要。
 */
@Service
public class DeletionPreviewService {

    public static final int TOKEN_TTL_SECONDS = 30;

    private final AircraftV2Mapper aircraftMapper;
    private final DeletionTokenMapper tokenMapper;
    private final InstructionV2Mapper instructionMapper;
    private final org.bluesky.training.common.TerminalAccessPolicy terminalAccessPolicy;
    private final byte[] secretKey;

    public DeletionPreviewService(AircraftV2Mapper aircraftMapper,
                                  DeletionTokenMapper tokenMapper,
                                  InstructionV2Mapper instructionMapper,
                                  org.bluesky.training.common.TerminalAccessPolicy
                                          terminalAccessPolicy,
                                  // 生产必须通过受限配置注入；默认值仅供本地演示
                                  @Value("${training.deletion-token-secret:"
                                          + "local-only-deletion-token-secret}"
                                  ) String secret) {
        this.aircraftMapper = aircraftMapper;
        this.tokenMapper = tokenMapper;
        this.instructionMapper = instructionMapper;
        this.terminalAccessPolicy = terminalAccessPolicy;
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 预览：显示呼号、位置、责任席位和活动指令，并签发一次性 token。 */
    @Transactional
    public Map<String, Object> createPreview(org.bluesky.training.common.CallerContext caller,
                                             String aircraftId, long aircraftRevision,
                                             String requestId) {
        Map<String, Object> aircraft = requireAircraft(aircraftId);
        String groupId = String.valueOf(aircraft.get("exercise_group_id"));
        terminalAccessPolicy.requireSameGroup(caller, groupId);
        new org.bluesky.training.common.RevisionGuard().requireExpected(
                aircraftRevision, ((Number) aircraft.get("revision")).longValue());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aircraftId", aircraftId);
        body.put("callsign", aircraft.get("callsign"));
        body.put("latitude", aircraft.get("latitude"));
        body.put("longitude", aircraft.get("longitude"));
        body.put("responsibleTerminalId",
                terminalOf(aircraftMapper.findCurrentAssignment(aircraftId)));
        body.put("activeInstructionCount",
                instructionMapper.countActiveByAircraft(aircraftId));
        body.put("lifecycle", aircraft.get("lifecycle"));
        body.put("confirmationRequired", true);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aircraftId", aircraftId);
        claims.put("revision", aircraftRevision);
        claims.put("terminalId", caller.terminalId());
        claims.put("requestId", requestId == null ? "req-" + UUID.randomUUID() : requestId);
        long expiresAtEpochSeconds =
                System.currentTimeMillis() / 1000 + TOKEN_TTL_SECONDS;
        claims.put("expiresAtEpochSeconds", expiresAtEpochSeconds);
        String token = DeletionTokenCodec.encode(claims, secretKey);
        // 摘要落库（评审 P0-5）：消费凭据入库，客户端只拿 token 本身
        tokenMapper.insertToken(DeletionTokenCodec.hashForStorage(token), aircraftId,
                caller.terminalId(), aircraftRevision,
                Timestamp.from(java.time.Instant.ofEpochSecond(expiresAtEpochSeconds)));
        body.put("confirmationToken", token);
        return body;
    }

    /**
     * 校验并原子消费：签名/过期/已用/版本变化/他席使用均拒绝
     * （详细设计 7.9/12.1；评审 P0-5 重放防护）。
     */
    @Transactional
    public Map<String, Object> validateAndConsumeToken(String token, String aircraftId,
                                                       long currentRevision,
                                                       String terminalId) {
        Map<String, Object> claims =
                DeletionTokenCodec.decodeAndVerify(token, secretKey);
        if (!aircraftId.equals(claims.get("aircraftId"))) {
            throw confirmation("token 与航空器不匹配");
        }
        if (!terminalId.equals(claims.get("terminalId"))) {
            throw confirmation("token 不得由其他终端使用");
        }
        long boundRevision = ((Number) claims.get("revision")).longValue();
        if (boundRevision != currentRevision) {
            throw confirmation("航空器版本已变化，需重新预览");
        }
        long expiresAt = ((Number) claims.get("expiresAtEpochSeconds")).longValue();
        if (System.currentTimeMillis() / 1000 > expiresAt) {
            throw confirmation("确认 token 已过期（30 秒），需重新预览");
        }
        // 原子消费：0 行=已被使用或已过期，同一 token 不得重放
        int consumed = tokenMapper.consumeToken(DeletionTokenCodec.hashForStorage(token));
        if (consumed != 1) {
            throw confirmation("确认 token 已被使用或过期，需重新预览");
        }
        return claims;
    }

    private Map<String, Object> requireAircraft(String aircraftId) {
        Map<String, Object> aircraft = aircraftId == null ? null
                : aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "航空器不存在: " + aircraftId);
        }
        return aircraft;
    }

    private static String terminalOf(Map<String, Object> assignment) {
        return assignment == null ? null : String.valueOf(assignment.get("terminal_id"));
    }

    private static V2DomainException confirmation(String message) {
        return new V2DomainException("CONFIRMATION_REQUIRED", 428, message,
                Arrays.asList("X-Confirmation-Token"));
    }
}
