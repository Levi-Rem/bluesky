package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.*;
import org.bluesky.training.persistence.*;
import org.bluesky.training.exercise.*;
import org.bluesky.training.instruction.V2InstructionProgressEvaluator;
import org.bluesky.training.event.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;

/** Owns one subscription per persisted current instance, including after Java restart. */
@Component
@ConditionalOnProperty(name="bluesky.adapter.native-state-enabled", havingValue="true")
public class NativeStateRuntime {
    private final EngineInstanceMapper engines;
    private final EngineInstanceService instances;
    private final ExerciseGroupService groups;
    private final SimulationClockService clock;
    private final V2InstructionProgressEvaluator progress;
    private final DynamicFrameService dynamic;
    private final ReliableEventStreamService streams;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired private EngineRecoveryService recovery;

    public NativeStateRuntime(EngineInstanceMapper engines, EngineInstanceService instances,
            ExerciseGroupService groups, SimulationClockService clock, V2InstructionProgressEvaluator progress,
            DynamicFrameService dynamic, ReliableEventStreamService streams, JdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.engines=engines; this.instances=instances; this.groups=groups; this.clock=clock;
        this.progress=progress; this.dynamic=dynamic; this.streams=streams; this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelay=500)
    public void reconcileSubscriptions() {
        Set<String> live = new HashSet<>();
        for (EngineInstanceRow engine : engines.activeInstances()) {
            if (!engine.getId().equals(engines.findGroupCurrentInstanceId(engine.getExerciseGroupId()))) continue;
            live.add(engine.getId());
            Subscription sub = subscriptions.computeIfAbsent(engine.getId(), id -> {
                Subscription created = new Subscription(engine);
                created.subscriber.subscribe(engine.getStateEndpoint(), frame -> accept(created, frame),
                        ignored -> created.assembler.expireIncomplete(java.time.Instant.now()));
                return created;
            });
            long silence = System.currentTimeMillis() - sub.heartbeat;
            String state = jdbc.queryForObject("SELECT state FROM exercise_group WHERE id=?", String.class, engine.getExerciseGroupId());
            if (silence > 5000 && !"STARTING".equals(state) && !"ENDING".equals(state)) {
                if (!"DISCONNECTED".equals(engine.getState())) instances.markDisconnected(engine.getId());
                if (Arrays.asList("RUNNING","PAUSED","PAUSING","RESUMING").contains(state)) {
                    try { groups.enterRecovering(engine.getExerciseGroupId(), "原生引擎心跳超时"); }
                    catch (org.bluesky.training.common.V2DomainException concurrent) { /* next scan */ }
                }
            }
        }
        for (String id : new ArrayList<>(subscriptions.keySet())) {
            if (!live.contains(id)) subscriptions.remove(id).subscriber.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void accept(Subscription sub, Map<String,Object> frame) {
        String id=sub.engine.getId(), group=sub.engine.getExerciseGroupId();
        if (!id.equals(engines.findGroupCurrentInstanceId(group))) return;
        sub.heartbeat=System.currentTimeMillis();
        if ("HEALTH".equals(frame.get("messageType"))) {
            instances.touchHeartbeat(id);
            if (!"CONNECTED".equals(instances.stateOf(id))) instances.markConnected(id);
            return;
        }
        if (!"STATE_SNAPSHOT_CHUNK".equals(frame.get("messageType"))) return;
        Map<String,Object> chunk=(Map<String,Object>)frame.get("payload");
        try {
            sub.assembler.expireIncomplete(java.time.Instant.now());
            sub.assembler.acceptChunk(chunk);
            String snapshotId=String.valueOf(chunk.get("snapshotId"));
            if (!sub.assembler.isComplete(snapshotId)) return;
            String encoded=sub.assembler.assembleAndVerify(snapshotId,String.valueOf(chunk.get("checksum")));
            sub.assembler.remove(snapshotId);
            JsonNode pieces=json.readTree(encoded);
            List<Map<String,Object>> states=new ArrayList<>();
            double simTime=pieces.get(0).path("simulationTimeSeconds").asDouble();
            recovery.reconcileSnapshot(group,id,pieces,simTime);
            transaction.execute(tx -> {
                instances.assertCurrentInstance(group,id);
                String state=jdbc.queryForObject("SELECT state FROM exercise_group WHERE id=?",String.class,group);
                if ("RUNNING".equals(state)) clock.advanceFromFrame(group,(long)simTime);
                for (JsonNode piece:pieces) for (JsonNode aircraft:piece.path("aircraft")) {
                    List<String> ids=jdbc.queryForList("SELECT id FROM exercise_aircraft WHERE exercise_group_id=? AND callsign=? AND lifecycle='ACTIVE'",String.class,group,aircraft.path("callsign").asText());
                    if (ids.isEmpty()) continue;
                    String aircraftId=ids.get(0);
                    jdbc.update("UPDATE exercise_aircraft SET latitude=?,longitude=?,heading_degrees=?,altitude_feet=?,speed_knots=?,vertical_speed_feet_per_minute=? WHERE id=?",
                            aircraft.path("latitude").asDouble(),aircraft.path("longitude").asDouble(),aircraft.path("headingDegrees").asDouble(),aircraft.path("altitudeFeet").asDouble(),aircraft.path("speedKnots").asDouble(),aircraft.path("verticalSpeedFeetPerMinute").asDouble(),aircraftId);
                    if(aircraft.hasNonNull("flightPhase"))jdbc.update("UPDATE exercise_aircraft SET flight_phase=? WHERE id=?",aircraft.path("flightPhase").asText(),aircraftId);
                    Map<String,Object> value=json.convertValue(aircraft,Map.class); value.put("id",aircraftId);states.add(value);
                    if ("RUNNING".equals(state)) progress.evaluate(aircraftId,aircraft,simTime);
                }
                return null;
            });
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("aircraft",states);payload.put("simulationTimeSeconds",simTime);
            payload.put("fakeTargets",jdbc.queryForList("SELECT * FROM fake_target WHERE exercise_group_id=? AND target_kind='RADAR_SYNTHETIC' AND state IN ('ACTIVE','STOPPED')",group));
            Map<String,Object> event=dynamic.publishLatest(group,payload);
            for (String terminal:jdbc.queryForList("SELECT id FROM workstation_terminal WHERE exercise_group_id=? AND enabled=TRUE",String.class,group)) streams.publishDynamic(terminal,event);
        } catch (Exception invalid) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("原生状态帧投影失败: {}", invalid.getMessage());
        }
    }

    @PreDestroy public void close() { subscriptions.values().forEach(s -> s.subscriber.close());subscriptions.clear(); }
    private static class Subscription {
        final EngineInstanceRow engine; final AdapterStateSubscriber subscriber;
        final SnapshotChunkAssembler assembler=new SnapshotChunkAssembler();
        volatile long heartbeat=System.currentTimeMillis();
        Subscription(EngineInstanceRow e) { engine=e;subscriber=new AdapterStateSubscriber(e.getExerciseGroupId(),e.getId()); }
    }
}
