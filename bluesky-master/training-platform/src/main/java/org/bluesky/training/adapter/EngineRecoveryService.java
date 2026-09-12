package org.bluesky.training.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.bluesky.training.common.TransactionalOutboxService;
import org.bluesky.training.persistence.OutboxEventRow;
import org.bluesky.training.exercise.ExerciseGroupService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

/** Recovery completes only after a verified full snapshot and a real PAUSE acknowledgement. */
@Service
public class EngineRecoveryService {
    private final JdbcTemplate jdbc;
    private final TransactionalOutboxService outbox;
    private final ExerciseGroupService groups;
    public EngineRecoveryService(JdbcTemplate jdbc,TransactionalOutboxService outbox,ExerciseGroupService groups) {
        this.jdbc=jdbc;this.outbox=outbox;this.groups=groups;
    }
    @Transactional
    public void reconcileSnapshot(String group,String instance,JsonNode pieces,double simTime) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT state,revision,simulation_time_seconds FROM exercise_group WHERE id=? AND engine_instance_id=? FOR UPDATE",group,instance);
        if(rows.isEmpty() || !"RECOVERING".equals(rows.get(0).get("state")))return;
        Set<String> actual=new HashSet<>();for(JsonNode piece:pieces)for(JsonNode ac:piece.path("aircraft"))actual.add(ac.path("callsign").asText());
        List<String> expected=jdbc.queryForList("SELECT callsign FROM exercise_aircraft WHERE exercise_group_id=? AND lifecycle='ACTIVE'",String.class,group);
        if(!actual.containsAll(expected) || simTime+1<((Number)rows.get(0).get("simulation_time_seconds")).doubleValue())return;
        String key="recovery-pause:"+group+":"+rows.get(0).get("revision");
        if(outbox.hasAdapterAction(instance,key))return;
        String id=UUID.randomUUID().toString();
        outbox.enqueueAdapterAction(OutboxEventRow.adapterAction(id,group,"PAUSE","{\"recovery\":true}").routeTo(instance,"req-"+id,key));
    }
    @Scheduled(fixedDelay=1000)
    public void failStalledRecovery() {
        for(String id:jdbc.queryForList("SELECT id FROM exercise_group WHERE state='RECOVERING' AND updated_at < TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(3))",String.class)) {
            try{groups.failRecovery(id,"30 秒内未完成引擎快照对账与暂停确认");}
            catch(org.bluesky.training.common.V2DomainException concurrent) { /* state CAS */ }
        }
    }
}
