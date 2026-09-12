package org.bluesky.training.common;

import org.bluesky.training.persistence.TerminalAdminMapper;
import org.bluesky.training.persistence.TerminalAdminRow;
import org.bluesky.training.persistence.TrustedCallerBindingMapper;
import org.bluesky.training.persistence.TrustedCallerBindingRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P02：终端与受信绑定的运维管理（仅运维服务身份，详细设计 2.2 §9.2/§14）。 */
@Service
public class TerminalProvisioningService {

    private static final List<String> UNIT_MODES = Arrays.asList("MET", "IMP", "MIX");

    private final TerminalAdminMapper terminalMapper;
    private final TrustedCallerBindingMapper bindingMapper;
    private final ServiceAccessPolicy serviceAccessPolicy;

    public TerminalProvisioningService(TerminalAdminMapper terminalMapper,
                                       TrustedCallerBindingMapper bindingMapper,
                                       ServiceAccessPolicy serviceAccessPolicy) {
        this.terminalMapper = terminalMapper;
        this.bindingMapper = bindingMapper;
        this.serviceAccessPolicy = serviceAccessPolicy;
    }

    @Transactional
    public Map<String, Object> createTerminal(CallerContext caller, String exerciseGroupId,
                                              String name, BigDecimal frequencyMhz, String unitMode) {
        serviceAccessPolicy.requireOperations(caller);
        if (terminalMapper.countGroupById(exerciseGroupId) == 0) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + exerciseGroupId);
        }
        BigDecimal frequency = normalizeFrequency(frequencyMhz);
        String mode = unitMode == null ? "IMP" : unitMode.toUpperCase();
        if (!UNIT_MODES.contains(mode)) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "unitMode 必须是 MET/IMP/MIX", Arrays.asList("unitMode"));
        }
        if (terminalMapper.countByGroupAndFrequency(exerciseGroupId, frequency) > 0) {
            throw new V2DomainException("TERMINAL_FREQUENCY_IN_USE", 409,
                    "组内频率已被占用: " + frequency.toPlainString(),
                    Arrays.asList("frequencyMhz"));
        }
        String id = UUID.randomUUID().toString();
        terminalMapper.insert(id, name == null ? "机长席-" + id.substring(0, 8) : name,
                exerciseGroupId, frequency, mode);
        return toMap(terminalMapper.findById(id));
    }

    @Transactional
    public Map<String, Object> updateTerminal(CallerContext caller, String terminalId,
                                              long expectedRevision, String name,
                                              String unitMode, Boolean enabled) {
        serviceAccessPolicy.requireOperations(caller);
        TerminalAdminRow current = requireTerminalExists(terminalId);
        if (current.getRevision() != expectedRevision) {
            throw revisionConflict("终端", expectedRevision, current.getRevision());
        }
        if (unitMode != null && !UNIT_MODES.contains(unitMode.toUpperCase())) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "unitMode 必须是 MET/IMP/MIX", Arrays.asList("unitMode"));
        }
        if (terminalMapper.update(terminalId, expectedRevision, name,
                unitMode == null ? null : unitMode.toUpperCase(), enabled) != 1) {
            throw revisionConflict("终端", expectedRevision,
                    requireTerminalExists(terminalId).getRevision());
        }
        return toMap(terminalMapper.findById(terminalId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByGroup(CallerContext caller, String exerciseGroupId) {
        if (caller == null) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403, "缺少受信调用方身份");
        }
        boolean operations = caller.callerType() == CallerContext.CallerType.OPERATIONS;
        boolean terminalOfGroup = caller.callerType() == CallerContext.CallerType.TERMINAL
                && exerciseGroupId != null && exerciseGroupId.equals(caller.exerciseGroupId());
        if (!operations && !terminalOfGroup) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "只允许运维服务或组内终端查询终端列表");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (TerminalAdminRow row : terminalMapper.listByGroup(exerciseGroupId)) {
            items.add(toMap(row));
        }
        return items;
    }

    @Transactional
    public TrustedCallerBindingRow bindCallerCertificate(CallerContext caller, String terminalId,
                                                         long expectedRevision,
                                                         String certificateFingerprintDigest) {
        serviceAccessPolicy.requireOperations(caller);
        TerminalAdminRow terminal = requireTerminalExists(terminalId);
        if (certificateFingerprintDigest == null || certificateFingerprintDigest.trim().isEmpty()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "缺少证书指纹摘要", Arrays.asList("certificateFingerprintDigest"));
        }
        String digest = certificateFingerprintDigest.trim();
        TrustedCallerBindingRow existing = bindingMapper.findByFingerprintDigest(digest);
        if (existing != null && !terminalId.equals(existing.getTerminalId())) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 409,
                    "该证书指纹已绑定其他终端");
        }
        TrustedCallerBindingRow current = bindingMapper.findByTerminalId(terminalId);
        if (current == null) {
            if (expectedRevision != 0L) {
                throw revisionConflict("受信绑定", expectedRevision, 0L);
            }
            TrustedCallerBindingRow row = new TrustedCallerBindingRow();
            row.setId(UUID.randomUUID().toString());
            row.setTerminalId(terminalId);
            row.setExerciseGroupId(terminal.getExerciseGroupId());
            row.setCertificateFingerprintDigest(digest);
            row.setEnabled(true);
            bindingMapper.insert(row);
            return bindingMapper.findByTerminalId(terminalId);
        }
        if (current.getRevision() != expectedRevision
                || bindingMapper.updateDigest(terminalId, expectedRevision, digest) != 1) {
            TrustedCallerBindingRow latest = bindingMapper.findByTerminalId(terminalId);
            throw revisionConflict("受信绑定", expectedRevision,
                    latest == null ? 0L : latest.getRevision());
        }
        return bindingMapper.findByTerminalId(terminalId);
    }

    /** 仅供领域层兼容调用；HTTP v2 必须使用显式 expectedRevision 重载。 */
    @Transactional
    public Map<String, Object> updateTerminal(CallerContext caller, String terminalId,
                                              String name, String unitMode, Boolean enabled) {
        TerminalAdminRow current = requireTerminalExists(terminalId);
        return updateTerminal(caller, terminalId, current.getRevision(), name, unitMode, enabled);
    }

    /** 仅供领域层兼容调用；HTTP v2 必须使用显式 expectedRevision 重载。 */
    @Transactional
    public TrustedCallerBindingRow bindCallerCertificate(CallerContext caller, String terminalId,
                                                         String certificateFingerprintDigest) {
        TrustedCallerBindingRow current = bindingMapper.findByTerminalId(terminalId);
        return bindCallerCertificate(caller, terminalId,
                current == null ? 0L : current.getRevision(), certificateFingerprintDigest);
    }

    private TerminalAdminRow requireTerminalExists(String terminalId) {
        TerminalAdminRow terminal = terminalId == null ? null : terminalMapper.findById(terminalId);
        if (terminal == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "终端不存在: " + terminalId);
        }
        return terminal;
    }

    private static Map<String, Object> toMap(TerminalAdminRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.getId());
        body.put("name", row.getName());
        body.put("exerciseGroupId", row.getExerciseGroupId());
        body.put("frequencyMhz", row.getFrequency());
        body.put("unitMode", row.getUnitMode());
        body.put("enabled", row.isEnabled());
        body.put("revision", row.getRevision());
        return body;
    }

    private BigDecimal normalizeFrequency(BigDecimal frequencyMhz) {
        if (frequencyMhz == null || frequencyMhz.doubleValue() <= 0
                || frequencyMhz.doubleValue() >= 1000) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "frequencyMhz 必须是 0–1000 之间的 MHz 数值", Arrays.asList("frequencyMhz"));
        }
        // 设计 5.1：频率按 DECIMAL(6,3) 相同精度比较
        return frequencyMhz.setScale(3, RoundingMode.HALF_UP);
    }

    private static V2DomainException revisionConflict(String aggregate, long expected, long actual) {
        return new V2DomainException("REVISION_CONFLICT", 409,
                aggregate + " revision 已改变，期望 " + expected + " 实际 " + actual,
                java.util.Collections.singletonList("revision"));
    }
}
