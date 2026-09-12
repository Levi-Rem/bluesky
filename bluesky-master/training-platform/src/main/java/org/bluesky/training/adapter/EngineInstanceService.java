package org.bluesky.training.adapter;

import org.bluesky.training.persistence.EngineInstanceMapper;
import org.bluesky.training.common.V2DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** P04：每组当前引擎实例管理（详细设计 5.1：同组最多一个非 STOPPED 当前实例，迟到实例拒绝）。 */
@Service
public class EngineInstanceService {

    private final EngineInstanceMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ManagedEngineProcesses processes;

    public EngineInstanceService(EngineInstanceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public String createForGroup(String exerciseGroupId, String controlEndpoint,
                                 String stateEndpoint) {
        if (mapper.lockGroup(exerciseGroupId) == null) {
            throw new V2DomainException("EXERCISE_GROUP_NOT_FOUND", 404,
                    "训练组不存在: " + exerciseGroupId);
        }
        if (mapper.countActiveForGroup(exerciseGroupId) > 0) {
            throw new V2DomainException("ENGINE_INSTANCE_ALREADY_ACTIVE", 409,
                    "训练组已有未停止的引擎实例");
        }
        String id = UUID.randomUUID().toString();
        if (processes != null) {
            String[] endpoints = processes.start(exerciseGroupId, id, controlEndpoint, stateEndpoint);
            controlEndpoint = endpoints[0]; stateEndpoint = endpoints[1];
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) processes.abandon(id);
                    }
                });
        }
        mapper.insert(id, exerciseGroupId, controlEndpoint, stateEndpoint, processes==null?null:processes.processIdentifier(id));
        if (mapper.bindCurrentInstance(exerciseGroupId, id) != 1) {
            throw new IllegalStateException("绑定训练组当前引擎实例失败: " + exerciseGroupId);
        }
        return id;
    }

    @Transactional
    public void markConnected(String instanceId) {
        mapper.updateState(instanceId, "CONNECTED");
    }

    @Transactional
    public void markDegraded(String instanceId) {
        mapper.updateState(instanceId, "DEGRADED");
    }

    @Transactional
    public void markDisconnected(String instanceId) {
        mapper.updateState(instanceId, "DISCONNECTED");
    }

    @Transactional
    public void markStopped(String instanceId) {
        mapper.updateState(instanceId, "STOPPED");
        mapper.settleStoppedActions();
        String groupId = mapper.findGroupId(instanceId);
        if (groupId != null) {
            mapper.clearCurrentInstance(groupId, instanceId);
        }
    }

    /** Also close pending actions left by an older platform after a verified process exit. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000)
    @Transactional
    public void reconcileStoppedActions() { mapper.settleStoppedActions(); }

    @Transactional
    public void touchHeartbeat(String instanceId) {
        mapper.touchHeartbeat(instanceId);
    }

    @Transactional
    public void updateSnapshotChecksum(String instanceId, String checksum) {
        mapper.updateSnapshotChecksum(instanceId, checksum);
    }

    /** 迟到旧实例响应因实例 ID 不同而拒绝。 */
    @Transactional(readOnly = true)
    public void assertCurrentInstance(String exerciseGroupId, String instanceId) {
        String current = mapper.findGroupCurrentInstanceId(exerciseGroupId);
        if (!instanceId.equals(current)) {
            throw new AdapterProtocolException("STALE_ENGINE_INSTANCE",
                    "实例不是训练组当前实例: 期望 " + current + " 实际 " + instanceId);
        }
    }

    @Transactional(readOnly = true)
    public String stateOf(String instanceId) {
        return mapper.findState(instanceId);
    }

    @Transactional(readOnly = true)
    public long revisionOf(String instanceId) {
        Long revision = mapper.findRevision(instanceId);
        return revision == null ? 0L : revision;
    }

    @Transactional(readOnly = true)
    public String currentInstanceId(String exerciseGroupId) {
        return mapper.findGroupCurrentInstanceId(exerciseGroupId);
    }

    public boolean hasProcessStopped(String instanceId) { return processes!=null && processes.hasStopped(instanceId); }
}
