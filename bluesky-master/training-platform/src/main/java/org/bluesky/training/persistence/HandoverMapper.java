package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** P08：移交与责任分配持久化（V10 aircraft_assignment / aircraft_handover）。 */
public interface HandoverMapper {

    /** 锁顺序第一步：航空器行 FOR UPDATE（详细设计 5.3.3）。 */
    @Select("SELECT id, exercise_group_id, assigned_terminal_id, lifecycle, revision "
            + "FROM exercise_aircraft WHERE id = #{aircraftId} FOR UPDATE")
    Map<String, Object> lockAircraftForUpdate(@Param("aircraftId") String aircraftId);

    /** 锁顺序第二步：当前分配行 FOR UPDATE。 */
    @Select("SELECT id, aircraft_id, terminal_id, started_at FROM aircraft_assignment "
            + "WHERE aircraft_id = #{aircraftId} AND ended_at IS NULL FOR UPDATE")
    Map<String, Object> lockCurrentAssignmentForUpdate(@Param("aircraftId") String aircraftId);

    @Insert("INSERT INTO aircraft_handover (id, aircraft_id, source_terminal_id, "
            + "target_terminal_id, target_frequency_mhz, aircraft_revision) "
            + "VALUES (#{id}, #{aircraftId}, #{sourceTerminalId}, #{targetTerminalId}, "
            + "#{targetFrequencyMhz}, #{aircraftRevision})")
    int insertHandover(@Param("id") String id,
                       @Param("aircraftId") String aircraftId,
                       @Param("sourceTerminalId") String sourceTerminalId,
                       @Param("targetTerminalId") String targetTerminalId,
                       @Param("targetFrequencyMhz") BigDecimal targetFrequencyMhz,
                       @Param("aircraftRevision") long aircraftRevision);

    @Update("UPDATE exercise_aircraft SET revision = revision + 1, "
            + "assigned_terminal_id = #{terminalId}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{aircraftId} AND revision = #{expectedRevision}")
    int incrementRevisionAndReassign(@Param("aircraftId") String aircraftId,
                                     @Param("expectedRevision") long expectedRevision,
                                     @Param("terminalId") String terminalId);

    @Select("SELECT id, name, frequency, enabled FROM workstation_terminal "
            + "WHERE exercise_group_id = #{groupId} AND frequency = #{frequency}")
    Map<String, Object> findTerminalByFrequency(@Param("groupId") String groupId,
                                                @Param("frequency") BigDecimal frequency);

    @Select("SELECT a.id, a.terminal_id AS \"terminalId\", a.started_at AS \"startedAt\", "
            + "a.ended_at AS \"endedAt\", c.callsign AS \"callsign\" "
            + "FROM aircraft_assignment a JOIN exercise_aircraft c ON c.id = a.aircraft_id "
            + "WHERE c.exercise_group_id = #{groupId} AND a.ended_at IS NULL "
            + "ORDER BY c.callsign")
    List<Map<String, Object>> listCurrentAssignments(@Param("groupId") String groupId);

    /** 活动与等待指令摘要：移交不取消、不改变它们（详细设计 5.3.6）。 */
    @Select("SELECT id, instruction_type AS \"type\", status FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} "
            + "AND status IN ('RECEIVED', 'VALIDATED', 'BLOCKED', 'DISPATCHING', 'EXECUTING') ORDER BY created_at")
    List<Map<String, Object>> activeInstructionSummaries(@Param("aircraftId") String aircraftId);
}
