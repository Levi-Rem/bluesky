package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** P06：可靠业务事件持久化（V10 business_event / terminal_event_delivery / sse_stream_epoch）。 */
public interface BusinessEventMapper {

    @Insert("INSERT INTO business_event (id, exercise_group_id, group_sequence, event_type, "
            + "source_outbox_id, entity_id, system_time_utc, simulation_time_seconds, payload) "
            + "VALUES (#{id}, #{groupId}, #{groupSequence}, #{eventType}, #{sourceOutboxId}, #{entityId}, "
            + "#{systemTimeUtc}, #{simulationTimeSeconds}, #{payload})")
    int insertBusinessEvent(@Param("id") String id,
                            @Param("groupId") String groupId,
                            @Param("groupSequence") long groupSequence,
                            @Param("eventType") String eventType,
                            @Param("sourceOutboxId") String sourceOutboxId,
                            @Param("entityId") String entityId,
                            @Param("systemTimeUtc") java.sql.Timestamp systemTimeUtc,
                            @Param("simulationTimeSeconds") double simulationTimeSeconds,
                            @Param("payload") String payload);

    @Select("SELECT group_sequence FROM business_event WHERE source_outbox_id = #{outboxId}")
    Long findGroupSequenceBySourceOutboxId(@Param("outboxId") String outboxId);

    @Insert("INSERT INTO terminal_event_delivery (id, terminal_id, stream_epoch, "
            + "delivery_sequence, business_event_id) "
            + "VALUES (#{id}, #{terminalId}, #{epoch}, #{deliverySequence}, #{businessEventId})")
    int insertDelivery(@Param("id") String id,
                       @Param("terminalId") String terminalId,
                       @Param("epoch") String epoch,
                       @Param("deliverySequence") long deliverySequence,
                       @Param("businessEventId") String businessEventId);

    @Select("SELECT d.delivery_sequence AS \"deliverySequence\", d.stream_epoch AS \"epoch\", "
            + "d.terminal_id AS \"terminalId\", b.exercise_group_id AS \"groupId\", "
            + "b.group_sequence AS \"groupSequence\", b.event_type AS \"eventType\", "
            + "b.entity_id AS \"entityId\", b.system_time_utc AS \"systemTimeUtc\", "
            + "b.simulation_time_seconds AS \"simulationTimeSeconds\", "
            + "b.payload AS \"payload\", d.created_at AS \"createdAt\" "
            + "FROM terminal_event_delivery d JOIN business_event b "
            + "ON b.id = d.business_event_id "
            + "WHERE d.terminal_id = #{terminalId} AND d.stream_epoch = #{epoch} "
            + "AND d.delivery_sequence > #{afterSequence} "
            + "ORDER BY d.delivery_sequence LIMIT #{limit}")
    List<Map<String, Object>> loadAfter(@Param("terminalId") String terminalId,
                                        @Param("epoch") String epoch,
                                        @Param("afterSequence") long afterSequence,
                                        @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(delivery_sequence), 0) FROM terminal_event_delivery "
            + "WHERE terminal_id = #{terminalId} AND stream_epoch = #{epoch}")
    long maxDeliverySequence(@Param("terminalId") String terminalId,
                             @Param("epoch") String epoch);

    @Select("SELECT COUNT(*) FROM terminal_event_delivery "
            + "WHERE terminal_id = #{terminalId} AND stream_epoch = #{epoch}")
    long countDeliveries(@Param("terminalId") String terminalId, @Param("epoch") String epoch);

    @Select("SELECT COALESCE(MIN(delivery_sequence), 0) FROM terminal_event_delivery "
            + "WHERE terminal_id = #{terminalId} AND stream_epoch = #{epoch}")
    long oldestRetainedSequence(@Param("terminalId") String terminalId,
                                @Param("epoch") String epoch);

    @Select("SELECT COUNT(*) FROM sse_stream_epoch WHERE singleton = 0")
    int countEpochRow();

    @Insert("INSERT INTO sse_stream_epoch (singleton, epoch) VALUES (0, #{epoch})")
    int insertEpoch(@Param("epoch") String epoch);

    @Select("SELECT epoch FROM sse_stream_epoch WHERE singleton = 0")
    String findEpoch();

    @org.apache.ibatis.annotations.Update("UPDATE sse_stream_epoch SET epoch = #{epoch}, "
            + "rebuilt_at = CURRENT_TIMESTAMP(3) WHERE singleton = 0")
    int updateEpoch(@Param("epoch") String epoch);

    @Select("SELECT state FROM exercise_group WHERE id = #{groupId}")
    String findGroupState(@Param("groupId") String groupId);

    @Select("SELECT simulation_time_seconds FROM exercise_group WHERE id = #{groupId}")
    Double findGroupSimulationTime(@Param("groupId") String groupId);

    @Select("SELECT id FROM workstation_terminal WHERE exercise_group_id = #{groupId} "
            + "AND enabled = 1 ORDER BY id")
    List<String> listEnabledTerminalIds(@Param("groupId") String groupId);

    @Select("SELECT id, name, exercise_group_id AS \"exerciseGroupId\", frequency AS \"frequencyMhz\", "
            + "unit_mode AS \"unitMode\", enabled, revision FROM workstation_terminal "
            + "WHERE id = #{terminalId} AND exercise_group_id = #{groupId}")
    Map<String, Object> findBootstrapTerminal(@Param("terminalId") String terminalId,
                                               @Param("groupId") String groupId);

    @Select("SELECT id, name, state, state_reason AS \"stateReason\", "
            + "simulation_time_seconds AS \"simulationTimeSeconds\", revision "
            + "FROM exercise_group WHERE id = #{groupId}")
    Map<String, Object> findBootstrapGroup(@Param("groupId") String groupId);

    @Select("SELECT e.id, e.state, e.protocol_version AS \"protocolVersion\", "
            + "e.reference_snapshot_checksum AS \"referenceSnapshotChecksum\", e.revision "
            + "FROM engine_instance e JOIN exercise_group g ON g.engine_instance_id = e.id "
            + "WHERE g.id = #{groupId}")
    Map<String, Object> findBootstrapEngine(@Param("groupId") String groupId);

    @Select("SELECT r.id, r.version_label AS \"versionLabel\", "
            + "r.manifest_checksum AS \"manifestChecksum\", r.revision "
            + "FROM reference_snapshot r JOIN exercise_group g ON g.reference_snapshot_id = r.id "
            + "WHERE g.id = #{groupId}")
    Map<String, Object> findBootstrapReferenceSnapshot(@Param("groupId") String groupId);

    @Select("SELECT id, assigned_terminal_id AS \"assignedTerminalId\", callsign, "
            + "aircraft_type AS \"aircraftType\", latitude, longitude, heading_degrees AS \"headingDegrees\", "
            + "altitude_feet AS \"altitudeFeet\", speed_knots AS \"speedKnots\", "
            + "vertical_speed_feet_per_minute AS \"verticalSpeedFeetPerMinute\", origin, destination, wake_category AS \"wakeCategory\", current_squawk AS \"transponderCode\", lifecycle, flight_phase AS \"flightPhase\", active_instruction_text AS \"activeInstructionText\", revision "
            + "FROM exercise_aircraft WHERE exercise_group_id = #{groupId} AND lifecycle<>'DELETED' ORDER BY callsign")
    List<Map<String, Object>> listBootstrapAircraft(@Param("groupId") String groupId);

    @Select("SELECT i.id, i.exercise_aircraft_id AS \"aircraftId\", i.raw_text AS \"rawText\", "
            + "i.instruction_type AS \"instructionType\", i.status, "
            + "i.sequence_number AS \"sequenceNumber\", i.failure_code AS \"failureCode\", i.revision "
            + "FROM aircraft_instruction i JOIN exercise_aircraft a ON a.id = i.exercise_aircraft_id "
            + "WHERE a.exercise_group_id = #{groupId} "
            + "AND i.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT') "
            + "ORDER BY i.sequence_number")
    List<Map<String, Object>> listBootstrapInstructions(@Param("groupId") String groupId);
}
