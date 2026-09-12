package org.bluesky.training.report;

import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * P17：报告写入（详细设计 2.2 §5.5/§12.2）。
 * 命令报告按指令状态转移唯一（uq instruction_id+transition_sequence）；
 * 飞行报告按 sourceEventId 去重（uq aircraft_id+source_event_id）。
 */
@Service
public class ReportWriteService {

    private final JdbcTemplate jdbc;
    private final InstructionV2Mapper instructionMapper;

    public ReportWriteService(JdbcTemplate jdbc, InstructionV2Mapper instructionMapper) {
        this.jdbc = jdbc;
        this.instructionMapper = instructionMapper;
    }

    /** 指令到达终态时写命令报告；同键重复（重放/并发帧）静默幂等。 */
    public void writeCommandTerminalReport(String instructionId, String fromStatus,
                                           String toStatus, String reasonCode,
                                           double simulationTimeSeconds) {
        Map<String, Object> instruction = instructionMapper.findById(instructionId);
        if (instruction == null) {
            return;
        }
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_report WHERE instruction_id = ? "
                        + "AND to_status = ?", Integer.class, instructionId, toStatus);
        if (existing != null && existing > 0) {
            return; // 同一终态只记一次（重放/并发帧幂等）
        }
        String normalized = normalizedOf(String.valueOf(instruction.get("parsed_payload")));
        int nextSequence = nextTransitionSequence(instructionId);
        try {
            jdbc.update("INSERT INTO command_report (id, exercise_group_id, instruction_id, "
                            + "transition_sequence, terminal_id, from_status, to_status, "
                            + "reason_code, description, raw_text, normalized_command, "
                            + "simulation_time_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    instruction.get("exercise_group_id"), instructionId, nextSequence,
                    instruction.get("source_terminal_id"), fromStatus, toStatus,
                    reasonCode, ReportQueryService.terminalDescription(reasonCode),
                    instruction.get("raw_text"), normalized, simulationTimeSeconds);
        } catch (DuplicateKeyException ignored) {
            // 幂等：同一转移已记录
        }
    }

    /** 终态收口统一入口：从指令行读取 reason/sim 时间，任何终态恰好一条报告。 */
    public void writeCommandTerminalReport(String instructionId, String toStatus) {
        Map<String, Object> instruction = instructionMapper.findById(instructionId);
        if (instruction == null) {
            return;
        }
        String reasonCode = str(instruction.get("failure_code"));
        double simulationTime = groupSimulationTime(
                String.valueOf(instruction.get("exercise_group_id")));
        writeCommandTerminalReport(instructionId, null, toStatus, reasonCode, simulationTime);
    }

    private double groupSimulationTime(String groupId) {
        Double seconds = jdbc.queryForObject(
                "SELECT simulation_time_seconds FROM exercise_group WHERE id = ?",
                Double.class, groupId);
        return seconds == null ? 0.0 : seconds;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    /** 飞行事件报告（TARGET_REACHED/TAKEOFF/LANDED/MISSED_APPROACH…）；sourceEventId 去重。 */
    public void writeFlightReport(String groupId, String aircraftId, String terminalId,
                                  String eventType, String sourceEventId, String detail,
                                  double simulationTimeSeconds) {
        try {
            jdbc.update("INSERT INTO flight_report (id, exercise_group_id, aircraft_id, "
                            + "terminal_id, event_type, source_event_id, detail, "
                            + "simulation_time_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), groupId, aircraftId, terminalId,
                    eventType, sourceEventId, detail, simulationTimeSeconds);
        } catch (DuplicateKeyException ignored) {
            // 幂等：同一事件源已记录
        }
    }

    private int nextTransitionSequence(String instructionId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(transition_sequence), 0) FROM command_report "
                        + "WHERE instruction_id = ?", Integer.class, instructionId);
        return (max == null ? 0 : max) + 1;
    }

    static String normalizedOf(String parsedPayload) {
        if (parsedPayload == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind
                    .ObjectMapper().readTree(parsedPayload);
            String normalized = node.path("normalizedText").asText(null);
            return normalized == null || normalized.trim().isEmpty()
                    ? node.path("normalized_text").asText(null) : normalized;
        } catch (java.io.IOException invalid) {
            return null;
        }
    }
}
