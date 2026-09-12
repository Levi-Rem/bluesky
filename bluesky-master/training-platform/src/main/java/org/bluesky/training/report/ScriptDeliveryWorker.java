package org.bluesky.training.report;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.bluesky.training.event.BusinessEventService;
import java.util.*;

@Component
public class ScriptDeliveryWorker {
    private final JdbcTemplate jdbc;
    private final BusinessEventService events;
    public ScriptDeliveryWorker(JdbcTemplate jdbc,BusinessEventService events){this.jdbc=jdbc;this.events=events;}
    @Scheduled(fixedDelay=500)
    @Transactional
    public void deliverDue() {
        for(Map<String,Object> row:jdbc.queryForList("SELECT s.* FROM exercise_script_item s JOIN exercise_group g ON g.id=s.exercise_group_id WHERE s.status='PENDING' AND g.state='RUNNING' AND s.trigger_simulation_time_seconds<=g.simulation_time_seconds")) {
            if(jdbc.update("UPDATE exercise_script_item SET status='DELIVERED',revision=revision+1 WHERE id=? AND status='PENDING'",row.get("id"))!=1)continue;
            row.put("status","DELIVERED");row.put("revision",((Number)row.get("revision")).longValue()+1);
            try{events.append(String.valueOf(row.get("exercise_group_id")),"script.delivered",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row),Arrays.asList(String.valueOf(row.get("target_terminal_ids")).split(",")));}
            catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
    }
}
