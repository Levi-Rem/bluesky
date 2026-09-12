package org.bluesky.training.adapter;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P04：每组当前引擎实例管理与迟到实例拒绝（详细设计 5.1 engine_instance）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class EngineInstanceServiceTest {

    @Test
    void stoppedInstanceClosesItsStopAndOutstandingActions() {
        String groupId=newGroup();
        String instance=instanceService.createForGroup(groupId,"tcp://127.0.0.1:9101","tcp://127.0.0.1:9102");
        for(String type:java.util.Arrays.asList("STOP","INSTRUCTION_APPLY"))
            jdbc.update("INSERT INTO outbox_event(id,outbox_kind,exercise_group_id,engine_instance_id,event_type,payload,payload_checksum,status) VALUES(?,'ADAPTER_ACTION',?,?,?,'{}','test','PENDING')",UUID.randomUUID().toString(),groupId,instance,type);
        instanceService.markStopped(instance);
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE engine_instance_id=? AND event_type='STOP'",String.class,instance));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE engine_instance_id=? AND event_type='INSTRUCTION_APPLY'",String.class,instance));
    }

    @Autowired
    private EngineInstanceService instanceService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroup() {
        String groupId = "group-eng-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "引擎实例组");
        return groupId;
    }

    @Test
    void givenCreatedInstanceWhenMarkedThroughLifecycleThenStatesHold() {
        String groupId = newGroup();

        String instanceId = instanceService.createForGroup(
                groupId, "tcp://127.0.0.1:9101", "tcp://127.0.0.1:9102");
        assertEquals("STARTING", instanceService.stateOf(instanceId));

        instanceService.markConnected(instanceId);
        assertEquals("CONNECTED", instanceService.stateOf(instanceId));

        instanceService.markDegraded(instanceId);
        assertEquals("DEGRADED", instanceService.stateOf(instanceId));

        instanceService.markDisconnected(instanceId);
        assertEquals("DISCONNECTED", instanceService.stateOf(instanceId));

        instanceService.markStopped(instanceId);
        assertEquals("STOPPED", instanceService.stateOf(instanceId));
    }

    @Test
    void givenTwoInstancesForSameGroupWhenAssertedThenOnlyCurrentPasses() {
        String groupId = newGroup();
        String current = instanceService.createForGroup(
                groupId, "tcp://127.0.0.1:9101", "tcp://127.0.0.1:9102");
        instanceService.assertCurrentInstance(groupId, current);

        org.bluesky.training.common.V2DomainException duplicate = assertThrows(
                org.bluesky.training.common.V2DomainException.class,
                () -> instanceService.createForGroup(
                        groupId, "tcp://127.0.0.1:9103", "tcp://127.0.0.1:9104"));
        assertEquals("ENGINE_INSTANCE_ALREADY_ACTIVE", duplicate.code());

        instanceService.markStopped(current);
        String replacement = instanceService.createForGroup(
                groupId, "tcp://127.0.0.1:9103", "tcp://127.0.0.1:9104");
        instanceService.assertCurrentInstance(groupId, replacement);
        AdapterProtocolException stale = assertThrows(AdapterProtocolException.class,
                () -> instanceService.assertCurrentInstance(groupId, current));
        assertEquals("STALE_ENGINE_INSTANCE", stale.code());
    }

    @Test
    void givenStateTransitionWhenAppliedThenRevisionIncrements() {
        String groupId = newGroup();
        String instanceId = instanceService.createForGroup(
                groupId, "tcp://127.0.0.1:9105", "tcp://127.0.0.1:9106");
        long before = instanceService.revisionOf(instanceId);

        instanceService.markConnected(instanceId);

        assertEquals(before + 1, instanceService.revisionOf(instanceId));
    }
}
