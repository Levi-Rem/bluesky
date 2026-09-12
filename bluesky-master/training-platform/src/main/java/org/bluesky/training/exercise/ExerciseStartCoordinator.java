package org.bluesky.training.exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.adapter.EngineInstanceService;
import org.bluesky.training.aircraft.AircraftAppearanceScheduler;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.OutboxEventRow;
import org.bluesky.training.reference.ReferenceSnapshotService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** STARTING 可重入编排：校验快照、建实例、HELLO、装载快照，最后才发送 START。 */
@Service
public class ExerciseStartCoordinator {

    private final ReferenceSnapshotService snapshotService;
    private final EngineInstanceService engineInstanceService;
    private final TransactionalOutboxService outboxService;
    private final AircraftAppearanceScheduler appearanceScheduler;
    private final String controlEndpoint;
    private final String stateEndpoint;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExerciseStartCoordinator(
            ReferenceSnapshotService snapshotService,
            EngineInstanceService engineInstanceService,
            TransactionalOutboxService outboxService,
            AircraftAppearanceScheduler appearanceScheduler,
            @Value("${bluesky.adapter.control-endpoint}") String controlEndpoint,
            @Value("${bluesky.adapter.state-endpoint}") String stateEndpoint) {
        this.snapshotService = snapshotService;
        this.engineInstanceService = engineInstanceService;
        this.outboxService = outboxService;
        this.appearanceScheduler = appearanceScheduler;
        this.controlEndpoint = controlEndpoint;
        this.stateEndpoint = stateEndpoint;
    }

    /** 与 READY→STARTING 同事务执行；任何失败都不会留下孤立实例。 */
    @Transactional
    public String prepareStart(String groupId) {
        Map<String, Object> verification = snapshotService.verifyPinnedSnapshot(groupId);
        if (!Boolean.TRUE.equals(verification.get("valid"))) {
            throw new V2DomainException("REFERENCE_NOT_FOUND", 422,
                    "固定参考快照完整性校验失败: " + verification.get("problems"));
        }
        return engineInstanceService.createForGroup(groupId, controlEndpoint, stateEndpoint);
    }

    @Transactional
    public void enqueueHello(String groupId, String engineInstanceId) {
        enqueue(groupId, engineInstanceId, "HELLO",
                json(mapOf("protocolVersion", "2.0")), "start:hello:" + engineInstanceId);
    }

    @Transactional
    public void enqueueReferenceLoad(String groupId, String engineInstanceId) {
        enqueue(groupId, engineInstanceId, "REFERENCE_SNAPSHOT_LOAD",
                json(snapshotService.adapterLoadPayload(groupId)),
                "start:reference:" + engineInstanceId);
    }

    @Transactional
    public void enqueueStart(String groupId, String engineInstanceId) {
        int requested = appearanceScheduler.requestAllDuePlans(groupId);
        if (requested == 0 && !appearanceScheduler.hasCreateRequested(groupId)) {
            enqueueStartAction(groupId, engineInstanceId);
        }
    }

    /** 最后一架启动期航空器确认后才允许发送 START，避免组先进入 RUNNING。 */
    @Transactional
    public void continueAfterAircraftApplied(String groupId, String engineInstanceId) {
        if (appearanceScheduler.isGroupStarting(groupId)
                && !appearanceScheduler.hasCreateRequested(groupId)) {
            enqueueStartAction(groupId, engineInstanceId);
        }
    }

    /**
     * 失败补偿时尽力通知引擎停止（评审 C4）：compensateFailedStart 只回退平台状态，
     * 不向 Adapter 发 STOP 会留下孤儿引擎。幂等键绑定实例，重复补偿不重复发送。
     */
    @Transactional
    public void enqueueStop(String groupId, String engineInstanceId) {
        enqueue(groupId, engineInstanceId, "STOP", "{}", "compensate:stop:" + engineInstanceId);
    }

    private void enqueueStartAction(String groupId, String engineInstanceId) {
        enqueue(groupId, engineInstanceId, "START", "{}", "start:run:" + engineInstanceId);
    }

    public String expectedManifestChecksum(String groupId) {
        return snapshotService.pinnedManifestChecksum(groupId);
    }

    private void enqueue(String groupId, String engineInstanceId, String action,
                         String payload, String idempotencyKey) {
        String outboxId = UUID.randomUUID().toString();
        outboxService.enqueueAdapterAction(OutboxEventRow.adapterAction(
                outboxId, groupId, action, payload).routeTo(
                engineInstanceId, "req-" + outboxId, idempotencyKey));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("启动编排载荷序列化失败", e);
        }
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
