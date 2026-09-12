package org.bluesky.training.faketarget;

import org.bluesky.training.aircraft.AircraftDeletionSaga;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.event.BusinessEventService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;

@Service
public class FakeTargetRuntime {
    private final JdbcTemplate jdbc;
    private final AircraftDeletionSaga deletion;
    private final BusinessEventService events;
    private final TransactionTemplate tx;
    public FakeTargetRuntime(JdbcTemplate jdbc,AircraftDeletionSaga deletion,BusinessEventService events,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.deletion=deletion;this.events=events;tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=500)
    public void tick() {
        for(String id:jdbc.queryForList("SELECT f.id FROM fake_target f JOIN exercise_group g ON g.id=f.exercise_group_id WHERE g.state='RUNNING' AND f.state IN ('SCHEDULED','ACTIVE','STOPPED','DELETE_REQUESTED')",String.class)) {
            try{tx.execute(status->{advance(id);return null;});}
            catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("假目标推进失败 {}: {}",id,e.getMessage());}
        }
    }
    @Transactional
    public void advance(String id) {
        Map<String,Object> row=jdbc.queryForMap("SELECT f.*,g.simulation_time_seconds,g.state AS group_state FROM fake_target f JOIN exercise_group g ON g.id=f.exercise_group_id WHERE f.id=? FOR UPDATE",id);
        if(!"RUNNING".equals(row.get("group_state")))return;
        double now=n(row,"simulation_time_seconds"),start=n(row,"start_simulation_seconds"),expire=n(row,"expire_simulation_seconds");
        if(now<start)return;
        String state=String.valueOf(row.get("state")),next=state;
        if("SIMULATED_AIRCRAFT".equals(row.get("target_kind"))) {
            String lifecycle=jdbc.queryForObject("SELECT lifecycle FROM exercise_aircraft WHERE id=?",String.class,row.get("is_fake_aircraft_id"));
            if("DELETED".equals(lifecycle))next="DELETED";
            else if("CREATE_FAILED".equals(lifecycle) || "DELETE_FAILED".equals(lifecycle))next="FAILED";
            else if("ACTIVE".equals(lifecycle)) {
                next="ACTIVE";
                if(now>=expire || "DELETE_REQUESTED".equals(state)) {requestDelete(id);next="DELETE_REQUESTED";}
            }
        } else {
            double until=Math.min(now,expire),last=row.get("last_simulation_seconds")==null?start:n(row,"last_simulation_seconds");
            if("SCHEDULED".equals(state) || "ACTIVE".equals(state)) {
                double[] position=FakeTargetDomain.projectPosition(n(row,"latitude_deg"),n(row,"longitude_deg"),n(row,"true_heading_deg"),n(row,"ground_speed_kt"),Math.max(0,until-last));
                jdbc.update("UPDATE fake_target SET latitude_deg=?,longitude_deg=?,last_simulation_seconds=? WHERE id=?",position[0],position[1],until,id);
                next=now>=expire?"EXPIRED":"ACTIVE";
            } else if("STOPPED".equals(state) && now>=expire)next="EXPIRED";
        }
        if(!next.equals(state)) {
            jdbc.update("UPDATE fake_target SET state=?,revision=revision+1 WHERE id=?",next,id);
            Map<String,Object> payload=jdbc.queryForMap("SELECT * FROM fake_target WHERE id=?",id);
            try{events.append(String.valueOf(row.get("exercise_group_id")),"fake-target.changed",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload),
                    jdbc.queryForList("SELECT id FROM workstation_terminal WHERE exercise_group_id=? AND enabled=TRUE",String.class,row.get("exercise_group_id")));}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
    }
    @Transactional
    public void requestDelete(String id) {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM fake_target WHERE id=? FOR UPDATE",id);
        String aircraftId=String.valueOf(row.get("is_fake_aircraft_id"));
        String lifecycle=jdbc.queryForObject("SELECT lifecycle FROM exercise_aircraft WHERE id=?",String.class,aircraftId);
        if(!Arrays.asList("DELETE_REQUESTED","DELETED").contains(lifecycle)) {
            String terminal=jdbc.queryForObject("SELECT terminal_id FROM aircraft_assignment WHERE aircraft_id=? AND ended_at IS NULL",String.class,aircraftId);
            deletion.requestDelete(CallerContext.terminal(terminal,String.valueOf(row.get("exercise_group_id")),null),aircraftId,"fake-target:"+id);
        }
        jdbc.update("UPDATE fake_target SET state=?,revision=revision+1 WHERE id=?", "DELETED".equals(lifecycle)?"DELETED":"DELETE_REQUESTED",id);
    }
    private double n(Map<String,Object> row,String key){return ((Number)row.get(key)).doubleValue();}
}
