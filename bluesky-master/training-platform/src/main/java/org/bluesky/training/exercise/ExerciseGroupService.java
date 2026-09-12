package org.bluesky.training.exercise;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.adapter.EngineInstanceService;
import org.bluesky.training.common.AuditService;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.RevisionGuard;
import org.bluesky.training.common.ServiceAccessPolicy;
import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupLifecycleMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;
import org.bluesky.training.persistence.ExerciseGroupStateRow;
import org.bluesky.training.persistence.OutboxEventRow;
import org.bluesky.training.event.EventStreamService;
import org.bluesky.training.mapdata.ReferenceDataResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ExerciseGroupService {
    public static final String DEFAULT_GROUP_ID = "GROUP-DEFAULT";

    private final BootstrapMapper bootstrapMapper;
    private final SimulationGateway simulationGateway;
    private final EventStreamService eventStreamService;
    private final ReferenceDataResolver referenceDataResolver;

    // ---- v2（P05）：持久化过渡状态 + Outbox；v1 方法保持迁移门面不变 ----
    private final ExerciseGroupLifecycleMapper lifecycleMapper;
    private final TransactionalOutboxService outboxService;
    private final AuditService auditService;
    private final TerminalAccessPolicy terminalAccessPolicy;
    private final ServiceAccessPolicy serviceAccessPolicy;
    private final RevisionGuard revisionGuard;
    private final ExerciseGroupStateMachine stateMachine;
    private final ExerciseGroupProvisioningService provisioningService;
    private final ExerciseStartCoordinator startCoordinator;
    private final EngineInstanceService engineInstanceService;
    /** 迁移桥模式：无 2.0 引擎实例时生命周期动作经 v1 网关同步完成。 */
    private final boolean v1BridgeEnabled;
    /** 2.0 Outbox 派发不可用时桥必须接管（实例可能已注册但动作无人派发）。 */
    private final boolean outboxWorkersEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private org.bluesky.training.instruction.InstructionDispatchService dispatch;

    public ExerciseGroupService(BootstrapMapper bootstrapMapper, SimulationGateway simulationGateway,
                                EventStreamService eventStreamService,
                                ReferenceDataResolver referenceDataResolver,
                                ExerciseGroupLifecycleMapper lifecycleMapper,
                                TransactionalOutboxService outboxService,
                                AuditService auditService,
                                TerminalAccessPolicy terminalAccessPolicy,
                                ServiceAccessPolicy serviceAccessPolicy,
                                ExerciseGroupProvisioningService provisioningService,
                                ExerciseStartCoordinator startCoordinator,
                                EngineInstanceService engineInstanceService,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${bluesky.adapter.v1-bridge-enabled:false}")
                                        boolean v1BridgeEnabled,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${bluesky.outbox.workers-enabled:true}")
                                        boolean outboxWorkersEnabled) {
        this.bootstrapMapper = bootstrapMapper;
        this.simulationGateway = simulationGateway;
        this.eventStreamService = eventStreamService;
        this.referenceDataResolver = referenceDataResolver;
        this.lifecycleMapper = lifecycleMapper;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.terminalAccessPolicy = terminalAccessPolicy;
        this.serviceAccessPolicy = serviceAccessPolicy;
        this.provisioningService = provisioningService;
        this.startCoordinator = startCoordinator;
        this.engineInstanceService = engineInstanceService;
        this.v1BridgeEnabled = v1BridgeEnabled;
        this.outboxWorkersEnabled = outboxWorkersEnabled;
        this.revisionGuard = new RevisionGuard();
        this.stateMachine = new ExerciseGroupStateMachine();
    }

    @Transactional
    public ExerciseGroupResponse start(String groupId) {
        requireDefaultGroup(groupId);
        ExerciseGroupRow current = bootstrapMapper.findDefaultGroup();
        if ("RUNNING".equals(current.getState())) {
            // 幂等重开训：组态 RUNNING 时引擎必须同样运行（桥模式下两者可能
            // 因历史暂停错位）——resume 对运行中的引擎是无害幂等操作
            simulationGateway.resume();
            return new ExerciseGroupResponse(current);
        }
        if (!"READY".equals(current.getState())) {
            throw new IllegalStateException("训练组当前状态不能开始: " + current.getState());
        }

        referenceDataResolver.requireReady();
        simulationGateway.start();
        int changed = bootstrapMapper.transitionGroupState(groupId, "READY", "RUNNING");
        if (changed != 1) {
            throw new IllegalStateException("训练组状态已变化，请刷新后重试");
        }
        return publishCurrentState();
    }

    @Transactional
    public ExerciseGroupResponse pause(String groupId) {
        requireDefaultGroup(groupId);
        ExerciseGroupRow current = bootstrapMapper.findDefaultGroup();
        if ("PAUSED".equals(current.getState())) {
            return new ExerciseGroupResponse(current);
        }
        if (!"RUNNING".equals(current.getState())) {
            throw new IllegalStateException("训练组当前状态不能暂停: " + current.getState());
        }
        simulationGateway.pause();
        int changed = bootstrapMapper.transitionGroupState(groupId, "RUNNING", "PAUSED");
        if (changed != 1) {
            throw new IllegalStateException("训练组状态已变化，请刷新后重试");
        }
        return publishCurrentState();
    }

    @Transactional
    public ExerciseGroupResponse resume(String groupId) {
        requireDefaultGroup(groupId);
        ExerciseGroupRow current = bootstrapMapper.findDefaultGroup();
        if ("RUNNING".equals(current.getState())) {
            return new ExerciseGroupResponse(current);
        }
        if (!"PAUSED".equals(current.getState())) {
            throw new IllegalStateException("训练组当前状态不能继续: " + current.getState());
        }
        referenceDataResolver.requireReady();
        simulationGateway.resume();
        int changed = bootstrapMapper.transitionGroupState(groupId, "PAUSED", "RUNNING");
        if (changed != 1) {
            throw new IllegalStateException("训练组状态已变化，请刷新后重试");
        }
        dispatch.releaseTrainingPausedForGroup(groupId);
        return publishCurrentState();
    }

    private ExerciseGroupResponse publishCurrentState() {
        ExerciseGroupResponse response = new ExerciseGroupResponse(bootstrapMapper.findDefaultGroup());
        eventStreamService.publishAfterCommit("exercise-state", response);
        return response;
    }

    private void requireDefaultGroup(String groupId) {
        if (!DEFAULT_GROUP_ID.equals(groupId)) {
            throw new IllegalArgumentException("首版只支持默认训练组");
        }
    }

    // ================================================================= v2（P05）

    @Transactional
    public Map<String, Object> requestStart(CallerContext caller, String groupId, long expectedRevision) {
        return requestTransition(caller, groupId, expectedRevision, "REQUEST_START",
                "START", null, true);
    }

    @Transactional
    public Map<String, Object> requestPause(CallerContext caller, String groupId, long expectedRevision) {
        return requestTransition(caller, groupId, expectedRevision, "REQUEST_PAUSE",
                "PAUSE", null, true);
    }

    @Transactional
    public Map<String, Object> requestResume(CallerContext caller, String groupId, long expectedRevision) {
        return requestTransition(caller, groupId, expectedRevision, "REQUEST_RESUME",
                "RESUME", null, true);
    }

    /** 结束训练：仅训练编排方（详细设计 5.1/9.1）。 */
    @Transactional
    public Map<String, Object> requestEnd(CallerContext caller, String groupId,
                                          long expectedRevision, String reason) {
        serviceAccessPolicy.requireOrchestrator(caller);
        if (reason == null || reason.trim().isEmpty()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "结束训练必须携带非空 reason", Arrays.asList("reason"));
        }
        return requestTransition(caller, groupId, expectedRevision, "REQUEST_END",
                "STOP", reason.trim(), false);
    }

    /** 启动失败补偿：逆序回退到 READY 并记录原因（详细设计 5.1）。 */
    @Transactional
    public Map<String, Object> compensateFailedStart(String groupId, String reason) {
        return applyEvent(groupId, "FAIL_START", reason, null);
    }

    /** 生命周期结果与平台预期不一致时停止猜测运动状态，统一转入恢复。 */
    @Transactional
    public Map<String, Object> enterRecovering(String groupId, String reason) {
        return applyEvent(groupId, "ENTER_RECOVERING", reason, null);
    }

    @Transactional
    public Map<String,Object> completeRecovery(String groupId) {return applyEvent(groupId,"COMPLETE_RECOVERY",null,null);}

    @Transactional
    public Map<String,Object> failRecovery(String groupId,String reason) {return applyEvent(groupId,"FAIL_RECOVERY",reason,null);}

    @Transactional
    public Map<String,Object> retryRecovery(CallerContext caller,String groupId,long revision) {
        serviceAccessPolicy.requireOrchestrator(caller);
        revisionGuard.requireExpected(revision,requireGroup(groupId).getRevision());
        return applyEvent(groupId,"RETRY_RECOVERY",null,caller);
    }

    /** Adapter 生命周期确认：STARTED/PAUSED/RESUMED/STOPPED。 */
    @Transactional
    public Map<String, Object> onAdapterLifecycleResult(String groupId, String messageType) {
        switch (String.valueOf(messageType)) {
            case "STARTED":
                return applyEvent(groupId, "CONFIRM_STARTED", null, null);
            case "PAUSED":
                return applyEvent(groupId, "CONFIRM_PAUSED", null, null);
            case "RESUMED":
                return applyEvent(groupId, "CONFIRM_RESUMED", null, null);
            case "STOPPED":
                return applyEvent(groupId, "CONFIRM_ENDED", null, null);
            default:
                throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                        "未知生命周期确认: " + messageType);
        }
    }

    private Map<String, Object> requestTransition(CallerContext caller, String groupId,
                                                  long expectedRevision, String event,
                                                  String adapterAction, String reason,
                                                  boolean terminalInitiated) {
        if (terminalInitiated) {
            terminalAccessPolicy.requireSameGroup(caller, groupId);
        } else {
            serviceAccessPolicy.requireOrchestrator(caller);
        }
        ExerciseGroupStateRow current = requireGroup(groupId);
        revisionGuard.requireExpected(expectedRevision, current.getRevision());

        String target;
        try {
            target = stateMachine.transition(current.getState(), event);
        } catch (IllegalStateException illegal) {
            if (targetStateReached(current.getState(), event)) {
                // 重复请求：返回当前操作，不推进状态、不追加 Outbox
                return envelope(UUID.randomUUID().toString(), groupId,
                        current.getState(), current.getRevision());
            }
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    illegal.getMessage(), Arrays.asList("groupState"));
        }
        String engineInstanceId = engineInstanceService.currentInstanceId(groupId);
        if ("REQUEST_START".equals(event)) {
            provisioningService.startReadiness(groupId);
            engineInstanceId = startCoordinator.prepareStart(groupId);
            adapterAction = "HELLO";
        }
        Map<String, Object> result = commitTransition(groupId, current, target, event,
                adapterAction, reason, caller, engineInstanceId);
        // 迁移桥：2.0 派发器关闭时 Outbox 动作无人派发，STARTING/PAUSING/
        // RESUMING/STOPPING 将无限滞留——经 v1 网关同步执行并直接确认终态
        if (v1BridgeEnabled && !outboxWorkersEnabled && adapterAction != null) {
            switch (adapterAction) {
                case "HELLO":
                    // REQUEST_START 同样无 2.0 派发：同步启动引擎并确认，
                    // 否则 STARTING 永久滞留
                    simulationGateway.start();
                    return applyEvent(groupId, "CONFIRM_STARTED", null, null);
                case "PAUSE":
                    simulationGateway.pause();
                    return applyEvent(groupId, "CONFIRM_PAUSED", null, null);
                case "RESUME":
                    simulationGateway.resume();
                    return applyEvent(groupId, "CONFIRM_RESUMED", null, null);
                case "STOP":
                    // v1 网关无 stop 原语：停放引擎（ENDED 为平台侧终态）
                    simulationGateway.pause();
                    return applyEvent(groupId, "CONFIRM_ENDED", null, null);
                default:
                    break;
            }
        }
        return result;
    }

    private Map<String, Object> applyEvent(String groupId, String event,
                                           String reason, CallerContext caller) {
        ExerciseGroupStateRow current = requireGroup(groupId);
        String target;
        try {
            target = stateMachine.transition(current.getState(), event);
        } catch (IllegalStateException illegal) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    illegal.getMessage(), Arrays.asList("groupState"));
        }
        String adapterAction = null;
        return commitTransition(groupId, current, target, event, adapterAction, reason, caller, null);
    }

    private Map<String, Object> commitTransition(String groupId, ExerciseGroupStateRow current,
                                                 String target, String event,
                                                 String adapterAction, String reason,
                                                 CallerContext caller,
                                                 String engineInstanceId) {
        int changed = lifecycleMapper.updateState(groupId, current.getRevision(), target, reason);
        if (changed != 1) {
            throw new V2DomainException("REVISION_CONFLICT", 409,
                    "训练组版本已经改变", Arrays.asList("groupRevision"));
        }
        if("RUNNING".equals(target) && dispatch!=null)dispatch.releaseTrainingPausedForGroup(groupId);
        if (adapterAction != null) {
            if ("HELLO".equals(adapterAction) && engineInstanceId != null) {
                startCoordinator.enqueueHello(groupId, engineInstanceId);
            } else {
                String outboxId = UUID.randomUUID().toString();
                OutboxEventRow action = OutboxEventRow.adapterAction(
                        outboxId, groupId, adapterAction,
                        "{\"event\":\"" + event + "\"}");
                if (engineInstanceId != null) {
                    action.routeTo(engineInstanceId, "req-" + outboxId,
                            "group:" + event + ":" + engineInstanceId + ":" + current.getRevision());
                }
                outboxService.enqueueAdapterAction(action);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", target);
        payload.put("reason", reason);
        outboxService.enqueueBusinessEvent(OutboxEventRow.businessEvent(
                UUID.randomUUID().toString(), groupId, "group.state.changed",
                jsonOf(payload)));
        auditService.recordMutation(
                caller != null ? caller : CallerContext.orchestrator("SYSTEM", null),
                "GROUP_" + event, groupId,
                "req-" + UUID.randomUUID(), null, true, jsonOf(payload));
        return envelope(UUID.randomUUID().toString(), groupId, target, current.getRevision() + 1);
    }

    private static boolean targetStateReached(String state, String event) {
        // 重复请求判定：当前已处于该事件的目标过渡态
        switch (event) {
            case "REQUEST_START":
                return "STARTING".equals(state) || "RUNNING".equals(state);
            case "REQUEST_PAUSE":
                return "PAUSING".equals(state) || "PAUSED".equals(state);
            case "REQUEST_RESUME":
                return "RESUMING".equals(state) || "RUNNING".equals(state);
            default:
                return false;
        }
    }

    private ExerciseGroupStateRow requireGroup(String groupId) {
        ExerciseGroupStateRow current = groupId == null ? null : lifecycleMapper.findById(groupId);
        if (current == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404, "训练组不存在: " + groupId);
        }
        return current;
    }

    private static Map<String, Object> envelope(String operationId, String groupId,
                                                String state, long revision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("resourceId", groupId);
        body.put("state", state);
        body.put("revision", revision);
        body.put("warnings", java.util.Collections.emptyList());
        return body;
    }

    private static String jsonOf(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Outbox 载荷序列化失败", e);
        }
    }
}
