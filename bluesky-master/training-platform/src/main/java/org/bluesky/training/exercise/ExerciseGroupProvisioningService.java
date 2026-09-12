package org.bluesky.training.exercise;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.ServiceAccessPolicy;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.ExerciseGroupLifecycleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P05：运维身份创建/查询训练组（详细设计 2.2 §9.2，初始 READY）。 */
@Service
public class ExerciseGroupProvisioningService {

    private final ExerciseGroupLifecycleMapper lifecycleMapper;
    private final ServiceAccessPolicy serviceAccessPolicy;

    public ExerciseGroupProvisioningService(ExerciseGroupLifecycleMapper lifecycleMapper,
                                            ServiceAccessPolicy serviceAccessPolicy) {
        this.lifecycleMapper = lifecycleMapper;
        this.serviceAccessPolicy = serviceAccessPolicy;
    }

    @Transactional
    public Map<String, Object> createGroup(CallerContext caller, String name) {
        serviceAccessPolicy.requireOperations(caller);
        String id = UUID.randomUUID().toString();
        String groupName = name == null || name.trim().isEmpty()
                ? "训练组-" + id.substring(0, 8) : name.trim();
        lifecycleMapper.insertGroup(id, groupName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", groupName);
        body.put("state", "READY");
        body.put("revision", 1L);
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listGroups(CallerContext caller) {
        serviceAccessPolicy.requireOperations(caller);
        return lifecycleMapper.listGroups();
    }

    /** START 前置：固定快照 + 组内启用终端（详细设计 5.1）。 */
    @Transactional(readOnly = true)
    public boolean startReadiness(String groupId) {
        requireGroup(groupId);
        if (lifecycleMapper.countPinnedSnapshot(groupId) == 0) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "训练组尚未固定参考快照，禁止开始", Arrays.asList("referenceSnapshotId"));
        }
        if (lifecycleMapper.countEnabledTerminals(groupId) == 0) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "组内没有启用终端，禁止开始", Arrays.asList("terminals"));
        }
        return true;
    }

    private void requireGroup(String groupId) {
        if (groupId == null || lifecycleMapper.findById(groupId) == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + groupId);
        }
    }
}
