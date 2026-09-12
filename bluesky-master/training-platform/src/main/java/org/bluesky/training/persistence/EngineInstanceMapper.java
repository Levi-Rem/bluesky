package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** P04：引擎实例持久化（V9 engine_instance）。 */
public interface EngineInstanceMapper {

    String COLUMNS = "id, exercise_group_id, control_endpoint, state_endpoint, protocol_version, "
            + "process_identifier, state, last_outbound_sequence, last_inbound_sequence, "
            + "reference_snapshot_checksum, last_heartbeat_at, revision";

    @Insert("INSERT INTO engine_instance (id, exercise_group_id, control_endpoint, state_endpoint, "
            + "protocol_version, process_identifier, state) "
            + "VALUES (#{id}, #{exerciseGroupId}, #{controlEndpoint}, #{stateEndpoint}, "
            + "'2.0', #{processIdentifier}, 'STARTING')")
    int insert(@Param("id") String id,
               @Param("exerciseGroupId") String exerciseGroupId,
               @Param("controlEndpoint") String controlEndpoint,
               @Param("stateEndpoint") String stateEndpoint,
               @Param("processIdentifier") String processIdentifier);

    @Select("SELECT " + COLUMNS + " FROM engine_instance WHERE id = #{id}")
    EngineInstanceRow findById(@Param("id") String id);

    @Select("SELECT " + COLUMNS + " FROM engine_instance WHERE state <> 'STOPPED'")
    java.util.List<EngineInstanceRow> activeInstances();

    @Update("UPDATE engine_instance SET last_outbound_sequence = last_outbound_sequence + 1 WHERE id = #{id}")
    int incrementOutboundSequence(@Param("id") String id);

    @Select("SELECT state FROM engine_instance WHERE id = #{id}")
    String findState(@Param("id") String id);

    @Select("SELECT revision FROM engine_instance WHERE id = #{id}")
    Long findRevision(@Param("id") String id);

    @Select("SELECT engine_instance_id FROM exercise_group WHERE id = #{groupId}")
    String findGroupCurrentInstanceId(@Param("groupId") String groupId);

    @Select("SELECT id FROM exercise_group WHERE id = #{groupId} FOR UPDATE")
    String lockGroup(@Param("groupId") String groupId);

    @Select("SELECT COUNT(*) FROM engine_instance WHERE exercise_group_id = #{groupId} "
            + "AND state <> 'STOPPED'")
    int countActiveForGroup(@Param("groupId") String groupId);

    @Update("UPDATE exercise_group SET engine_instance_id = #{instanceId}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{groupId}")
    int bindCurrentInstance(@Param("groupId") String groupId,
                            @Param("instanceId") String instanceId);

    @Update("UPDATE exercise_group SET engine_instance_id = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{groupId} AND engine_instance_id = #{instanceId}")
    int clearCurrentInstance(@Param("groupId") String groupId,
                             @Param("instanceId") String instanceId);

    @Update("UPDATE engine_instance SET state = #{state}, revision = revision + 1 "
            + "WHERE id = #{id}")
    int updateState(@Param("id") String id, @Param("state") String state);

    @Update("UPDATE outbox_event SET status=CASE WHEN event_type='STOP' THEN 'CONFIRMED' ELSE 'FAILED' END, "
            + "claimed_by=NULL, claimed_at=NULL, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE outbox_kind='ADAPTER_ACTION' AND engine_instance_id IN (SELECT id FROM engine_instance WHERE state='STOPPED') "
            + "AND (status IN ('PENDING','SENT') OR (status='FAILED' AND event_type='STOP'))")
    int settleStoppedActions();

    @Update("UPDATE engine_instance SET last_heartbeat_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id}")
    int touchHeartbeat(@Param("id") String id);

    @Update("UPDATE engine_instance SET reference_snapshot_checksum = #{checksum}, "
            + "revision = revision + 1 WHERE id = #{id}")
    int updateSnapshotChecksum(@Param("id") String id, @Param("checksum") String checksum);

    @Select("SELECT exercise_group_id FROM engine_instance WHERE id = #{id}")
    String findGroupId(@Param("id") String id);
}
