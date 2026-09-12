package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** P05：训练组 v2 生命周期持久化（exercise_group + V8/V9 扩展列）。 */
public interface ExerciseGroupLifecycleMapper {
    @org.apache.ibatis.annotations.Select("SELECT CURRENT_TIMESTAMP(3)")
    java.time.LocalDateTime databaseNow();

    @Select("SELECT id, name, state, state_reason, simulation_time_seconds, revision "
            + "FROM exercise_group WHERE id = #{groupId}")
    ExerciseGroupStateRow findById(@Param("groupId") String groupId);

    @Update("UPDATE exercise_group SET state = #{newState}, "
            + "state_reason = #{stateReason}, revision = revision + 1, "
            + "started_at = CASE WHEN #{newState} = 'RUNNING' AND started_at IS NULL "
            + "THEN CURRENT_TIMESTAMP(3) ELSE started_at END, "
            + "ended_at = CASE WHEN #{newState} = 'ENDED' THEN CURRENT_TIMESTAMP(3) "
            + "ELSE ended_at END, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{groupId} AND revision = #{expectedRevision}")
    int updateState(@Param("groupId") String groupId,
                    @Param("expectedRevision") long expectedRevision,
                    @Param("newState") String newState,
                    @Param("stateReason") String stateReason);

    @Update("UPDATE exercise_group SET simulation_time_seconds = #{simulationSeconds}, "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{groupId} AND state = 'RUNNING' "
            // 严格小于：等值帧不得推进 revision（评审 C11，避免与生命周期动作乐观锁互相打架）
            + "AND simulation_time_seconds < #{simulationSeconds}")
    int advanceSimulationTime(@Param("groupId") String groupId,
                              @Param("simulationSeconds") long simulationSeconds);

    /** PAUSED 落库实际暂停时刻（详细设计 4.2.7；评审 C3）：同样不回退、等值不写。 */
    @Update("UPDATE exercise_group SET simulation_time_seconds = #{simulationSeconds}, "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{groupId} AND state = 'PAUSED' "
            + "AND simulation_time_seconds < #{simulationSeconds}")
    int freezeSimulationTime(@Param("groupId") String groupId,
                             @Param("simulationSeconds") long simulationSeconds);

    /** 过渡态超时扫描（评审 C2：PAUSING/RESUMING/STARTING/ENDING 5 秒看门狗）。 */
    @Select("<script>SELECT id, name, state, state_reason, simulation_time_seconds, revision "
            + "FROM exercise_group WHERE state IN "
            + "<foreach collection='states' item='state' open='(' separator=',' close=')'>"
            + "#{state}</foreach> AND updated_at &lt;= #{threshold}</script>")
    List<ExerciseGroupStateRow> findStaleTransitions(@Param("states") List<String> states,
                                                     @Param("threshold") java.time.LocalDateTime threshold);

    @Insert("INSERT INTO exercise_group (id, name, state) VALUES (#{id}, #{name}, 'READY')")
    int insertGroup(@Param("id") String id, @Param("name") String name);

    @Select("SELECT id, name, state, revision FROM exercise_group ORDER BY created_at DESC")
    List<Map<String, Object>> listGroups();

    @Select("SELECT COUNT(*) FROM workstation_terminal "
            + "WHERE exercise_group_id = #{groupId} AND enabled = 1")
    int countEnabledTerminals(@Param("groupId") String groupId);

    @Select("SELECT COUNT(*) FROM exercise_group "
            + "WHERE id = #{groupId} AND reference_snapshot_id IS NOT NULL")
    int countPinnedSnapshot(@Param("groupId") String groupId);
}
