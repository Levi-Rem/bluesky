package org.bluesky.training.exercise;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.persistence.BootstrapMapper;
import org.bluesky.training.persistence.ExerciseGroupRow;
import org.bluesky.training.event.EventStreamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExerciseGroupService {
    public static final String DEFAULT_GROUP_ID = "GROUP-DEFAULT";

    private final BootstrapMapper bootstrapMapper;
    private final SimulationGateway simulationGateway;
    private final EventStreamService eventStreamService;

    public ExerciseGroupService(BootstrapMapper bootstrapMapper, SimulationGateway simulationGateway,
                                EventStreamService eventStreamService) {
        this.bootstrapMapper = bootstrapMapper;
        this.simulationGateway = simulationGateway;
        this.eventStreamService = eventStreamService;
    }

    @Transactional
    public ExerciseGroupResponse start(String groupId) {
        requireDefaultGroup(groupId);
        ExerciseGroupRow current = bootstrapMapper.findDefaultGroup();
        if ("RUNNING".equals(current.getState())) {
            return new ExerciseGroupResponse(current);
        }
        if (!"READY".equals(current.getState())) {
            throw new IllegalStateException("训练组当前状态不能开始: " + current.getState());
        }

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
        simulationGateway.resume();
        int changed = bootstrapMapper.transitionGroupState(groupId, "PAUSED", "RUNNING");
        if (changed != 1) {
            throw new IllegalStateException("训练组状态已变化，请刷新后重试");
        }
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
}
