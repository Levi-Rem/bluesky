package org.bluesky.training.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P02：聚合训练态、飞行阶段与引擎可写性（详细设计 5.1 操作矩阵、7.3、10.1）。 */
class OperationPolicyTest {

    private final OperationPolicy policy = new OperationPolicy();

    @Test
    void givenRecoveringGroupWhenWritingThenEngineRecovering() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireGroupStateAllows("RECOVERING",
                        Arrays.asList("RUNNING", "PAUSED")));
        assertEquals(503, failure.httpStatus());
        assertEquals("ENGINE_RECOVERING", failure.code());

        V2DomainException endedAlsoRejected = assertThrows(V2DomainException.class,
                () -> policy.requireGroupStateAllows("ENDED", Arrays.asList("RUNNING", "PAUSED")));
        assertEquals(409, endedAlsoRejected.httpStatus());
        assertEquals("TRAINING_STATE_INVALID", endedAlsoRejected.code());
    }

    @Test
    void givenInstructionPhasesWhenCheckedThenMatrixHolds() {
        // 提交指令：RUNNING 允许，PAUSED 允许（接收并阻塞），READY 拒绝（详细设计 5.1）
        assertDoesNotThrow(() -> policy.requireGroupStateAllows("PAUSED",
                Arrays.asList("RUNNING", "PAUSED")));
        assertThrows(V2DomainException.class, () -> policy.requireGroupStateAllows("READY",
                Arrays.asList("RUNNING", "PAUSED")));
    }

    @Test
    void givenMissedApproachPhasesWhenCheckedThenOnlyApproachFinalFlareAllowed() {
        assertDoesNotThrow(() -> policy.requireAircraftPhaseAllows("APPROACH",
                Arrays.asList("APPROACH", "FINAL", "FLARE")));
        assertDoesNotThrow(() -> policy.requireAircraftPhaseAllows("FINAL",
                Arrays.asList("APPROACH", "FINAL", "FLARE")));
        assertDoesNotThrow(() -> policy.requireAircraftPhaseAllows("FLARE",
                Arrays.asList("APPROACH", "FINAL", "FLARE")));

        V2DomainException rollout = assertThrows(V2DomainException.class,
                () -> policy.requireAircraftPhaseAllows("ROLLOUT",
                        Arrays.asList("APPROACH", "FINAL", "FLARE")));
        assertEquals(409, rollout.httpStatus());
        assertEquals("PROCEDURE_STATE_INVALID", rollout.code());

        V2DomainException cruise = assertThrows(V2DomainException.class,
                () -> policy.requireAircraftPhaseAllows("CRUISE",
                        Arrays.asList("APPROACH", "FINAL", "FLARE")));
        assertEquals("PROCEDURE_STATE_INVALID", cruise.code());
    }

    @Test
    void givenEngineStatesWhenWritableCheckedThenOnlyConnectedOrDegradedPass() {
        assertDoesNotThrow(() -> policy.requireEngineWritable("CONNECTED"));
        assertDoesNotThrow(() -> policy.requireEngineWritable("DEGRADED"));

        V2DomainException disconnected = assertThrows(V2DomainException.class,
                () -> policy.requireEngineWritable("DISCONNECTED"));
        assertEquals(503, disconnected.httpStatus());
        assertEquals("ENGINE_RECOVERING", disconnected.code());

        V2DomainException stopped = assertThrows(V2DomainException.class,
                () -> policy.requireEngineWritable("STOPPED", Collections.emptyList()));
        assertEquals("ENGINE_RECOVERING", stopped.code());
    }
}
