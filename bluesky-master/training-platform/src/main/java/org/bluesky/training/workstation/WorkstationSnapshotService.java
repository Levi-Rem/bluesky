package org.bluesky.training.workstation;

import org.bluesky.training.event.StreamEpochService;
import org.bluesky.training.event.TerminalDeliveryService;
import org.bluesky.training.event.DynamicFrameService;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.BusinessEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** P06：bootstrap 同事务读取投影与最大投递序号（详细设计 9.5.4：快照与增量无窗口）。 */
@Service
public class WorkstationSnapshotService {

    private final BusinessEventMapper mapper;
    private final TerminalDeliveryService deliveryService;
    private final StreamEpochService epochService;
    private final DynamicFrameService dynamicFrameService;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${bluesky.adapter.native-state-enabled:false}") private boolean nativeAdapter;

    public WorkstationSnapshotService(BusinessEventMapper mapper,
                                      TerminalDeliveryService deliveryService,
                                      StreamEpochService epochService,
                                      DynamicFrameService dynamicFrameService) {
        this.mapper = mapper;
        this.deliveryService = deliveryService;
        this.epochService = epochService;
        this.dynamicFrameService = dynamicFrameService;
    }

    @Transactional
    public Map<String, Object> bootstrap(String terminalId, String groupId) {
        String epoch = epochService.currentEpoch();
        long snapshotSequence = deliveryService.maxDeliverySequence(terminalId, epoch);

        Map<String, Object> terminal = mapper.findBootstrapTerminal(terminalId, groupId);
        Map<String, Object> group = mapper.findBootstrapGroup(groupId);
        if (terminal == null || group == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "工作台终端或训练组不存在");
        }

        List<Map<String, Object>> aircraft = mapper.listBootstrapAircraft(groupId);
        for (Map<String,Object> item:aircraft) {
            List<String> routes=jdbc.queryForList("SELECT route_text FROM flight_plan WHERE aircraft_id=? ORDER BY plan_version DESC LIMIT 1",String.class,item.get("id"));
            item.put("route",routes.isEmpty()?Collections.emptyList():java.util.Arrays.asList(routes.get(0).split("\\s+")));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nativeAdapter",nativeAdapter);
        body.put("terminalId", terminalId);
        body.put("exerciseGroupId", groupId);
        body.put("streamEpoch", epoch);
        body.put("snapshotSequence", snapshotSequence);
        body.put("state", group.get("state"));
        body.put("deliveries", mapper.countDeliveries(terminalId, epoch));
        body.put("terminal", terminal);
        body.put("exerciseGroup", group);
        body.put("engine", mapper.findBootstrapEngine(groupId));
        body.put("referenceSnapshot", mapper.findBootstrapReferenceSnapshot(groupId));
        body.put("aircraft", aircraft);
        body.put("fakeTargets", jdbc.queryForList("SELECT * FROM fake_target WHERE exercise_group_id=? AND state NOT IN ('DELETED','EXPIRED')",groupId));
        body.put("assignments", assignmentProjection(aircraft));
        body.put("instructions", mapper.listBootstrapInstructions(groupId));
        List<Map<String,Object>> scripts=jdbc.queryForList("SELECT * FROM exercise_script_item WHERE exercise_group_id=? AND status IN ('DELIVERED','ACKNOWLEDGED')",groupId);
        scripts.removeIf(row->!java.util.Arrays.asList(String.valueOf(row.get("target_terminal_ids")).split(",")).contains(terminalId));
        body.put("scripts",scripts);
        body.put("messages",jdbc.queryForList("SELECT * FROM terminal_message WHERE exercise_group_id=? AND target_terminal_id=? AND deleted_at IS NULL",groupId,terminalId));
        body.put("displayProfiles",jdbc.queryForList("SELECT * FROM display_profile WHERE terminal_id=?",terminalId));
        body.put("latestDynamicFrame", dynamicFrameService.latestForGroup(groupId));
        return body;
    }

    private static java.util.List<Map<String, Object>> assignmentProjection(
            java.util.List<Map<String, Object>> aircraft) {
        java.util.List<Map<String, Object>> assignments = new java.util.ArrayList<>();
        for (Map<String, Object> item : aircraft) {
            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put("aircraftId", item.get("id"));
            assignment.put("terminalId", item.get("assignedTerminalId"));
            assignments.add(assignment);
        }
        return assignments;
    }
}
