package org.bluesky.training.report;

import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** P17 v2 报告查询接口（详细设计 2.2 §5.5/§9.2）：稳定倒序游标分页。 */
@V2Api
@RestController
@RequestMapping("/api/v2/exercise-groups/{groupId}/reports")
public class ReportControllerV2 {

    private final CallerContextResolver resolver;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ReportControllerV2(CallerContextResolver resolver,
                              org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable("groupId") String groupId,
                                     @RequestParam(value = "reportKind", defaultValue = "COMMAND") String reportKind,
                                     @RequestParam(value = "aircraftId", required = false) String aircraftId,
                                     @RequestParam(value = "terminalId", required = false) String terminalId,
                                     @RequestParam(value = "eventType", required = false) String eventType,
                                     @RequestParam(value = "fromSimulationTimeSeconds", required = false) Double fromSim,
                                     @RequestParam(value = "toSimulationTimeSeconds", required = false) Double toSim,
                                     @RequestParam(value = "pageSize", defaultValue = "100") int pageSize,
                                     @RequestParam(value = "cursor", required = false) String cursor,
                                     HttpServletRequest request) {
        resolver.resolve(request);
        StringBuilder where = new StringBuilder(" WHERE r.exercise_group_id = ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(groupId);
        if ("COMMAND".equals(reportKind)) {
            if (aircraftId != null) { where.append(" AND r.instruction_id IN "
                    + "(SELECT id FROM aircraft_instruction WHERE exercise_aircraft_id = ?) ");
                args.add(aircraftId); }
            if (terminalId != null) { where.append(" AND r.terminal_id = ? "); args.add(terminalId); }
        } else if ("FLIGHT".equals(reportKind)) {
            if (aircraftId != null) { where.append(" AND r.aircraft_id = ? "); args.add(aircraftId); }
            if (terminalId != null) { where.append(" AND r.terminal_id = ? "); args.add(terminalId); }
            if (eventType != null) { where.append(" AND r.event_type = ? "); args.add(eventType); }
        } else {
            throw new org.bluesky.training.common.V2DomainException(
                    "INVALID_INSTRUCTION", 400, "reportKind 必须是 COMMAND|FLIGHT",
                    java.util.Arrays.asList("reportKind"));
        }
        if (fromSim != null) { where.append(" AND r.simulation_time_seconds >= ? "); args.add(fromSim); }
        if (toSim != null) { where.append(" AND r.simulation_time_seconds <= ? "); args.add(toSim); }
        if (cursor != null && !cursor.trim().isEmpty()) {
            String[] parts = cursor.split(":", 2);
            if (parts.length == 2) {
                try {
                    where.append(" AND (r.simulation_time_seconds < ? OR "
                            + "(r.simulation_time_seconds = ? AND r.id < ?)) ");
                    double t = Double.parseDouble(parts[0]);
                    args.add(t); args.add(t); args.add(parts[1]);
                } catch (NumberFormatException ignored) {
                    // 非法游标视为第一页
                }
            }
        }
        int limit = Math.max(1, Math.min(pageSize, 200));
        // 报告项含类型/规范文本等完整字段（详细设计 9.2）；命令报告联指令表补类型
        String from;
        String columns;
        if ("COMMAND".equals(reportKind)) {
            columns = "r.id, r.instruction_id AS \"instructionId\", r.from_status "
                    + "AS \"fromStatus\", r.to_status AS \"toStatus\", r.reason_code "
                    + "AS \"reasonCode\", r.description, r.terminal_id AS \"terminalId\", "
                    + "r.raw_text AS \"rawText\", r.normalized_command AS \"normalizedCommand\", "
                    + "r.simulation_time_seconds AS \"simulationTimeSeconds\", "
                    + "i.instruction_type AS \"instructionType\"";
            from = " FROM command_report r LEFT JOIN aircraft_instruction i "
                    + "ON i.id = r.instruction_id";
        } else {
            columns = "r.id, r.aircraft_id AS \"aircraftId\", r.event_type AS \"eventType\", "
                    + "r.source_event_id AS \"sourceEventId\", r.detail, "
                    + "r.terminal_id AS \"terminalId\", "
                    + "r.simulation_time_seconds AS \"simulationTimeSeconds\"";
            from = " FROM flight_report r";
        }
        where.insert(0, from).insert(0, "SELECT " + columns)
                .append(" ORDER BY r.simulation_time_seconds DESC, r.id DESC LIMIT ")
                .append(limit + 1);
        java.util.List<Map<String, Object>> rows = jdbc.queryForList(
                where.toString(), args.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("hasMore", hasMore);
        if (!rows.isEmpty() && hasMore) {
            Map<String, Object> last = rows.get(rows.size() - 1);
            body.put("nextCursor", last.get("simulation_time_seconds") + ":" + last.get("id"));
        }
        return body;
    }
}
