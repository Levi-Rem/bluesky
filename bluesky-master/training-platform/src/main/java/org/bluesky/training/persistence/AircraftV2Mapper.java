package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** P07：航空器 v2 持久化（exercise_aircraft 扩展列 + flight_plan/leg/assignment）。 */
public interface AircraftV2Mapper {

    String AIRCRAFT_COLUMNS = "id, exercise_group_id, assigned_terminal_id, callsign, "
            + "aircraft_type, wake_category, origin, destination, latitude, longitude, "
            + "heading_degrees, altitude_feet, speed_knots, vertical_speed_feet_per_minute, "
            + "lifecycle, flight_phase, control_status, is_fake, icao24, planned_squawk, "
            + "current_squawk, ssr_mode, target_appearance_time, actual_appearance_time, "
            + "deleted_at, active_callsign_key, failure_code, failure_message, revision, "
            + "transponder_ident_active, transponder_ident_expires_at";

    @Insert("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
            + "callsign, aircraft_type, wake_category, origin, destination, latitude, longitude, "
            + "heading_degrees, altitude_feet, speed_knots, "
            + "lifecycle, is_fake, icao24, planned_squawk, current_squawk, ssr_mode, "
            + "target_appearance_time, active_callsign_key) "
            + "VALUES (#{id}, #{groupId}, #{terminalId}, #{callsign}, #{aircraftType}, "
            + "#{wakeCategory}, #{origin}, #{destination}, #{latitude}, #{longitude}, "
            + "#{trueHeadingDeg}, #{altitudeFtMsl}, #{indicatedAirspeedKt}, "
            + "'PLANNED', #{isFake}, #{icao24}, #{plannedSquawk}, #{plannedSquawk}, #{ssrMode}, "
            + "#{targetAppearanceTime}, #{callsign})")
    int insertPlannedAircraft(@Param("id") String id,
                              @Param("groupId") String groupId,
                              @Param("terminalId") String terminalId,
                              @Param("callsign") String callsign,
                              @Param("aircraftType") String aircraftType,
                              @Param("wakeCategory") String wakeCategory,
                              @Param("origin") String origin,
                              @Param("destination") String destination,
                              @Param("latitude") double latitude,
                              @Param("longitude") double longitude,
                              @Param("trueHeadingDeg") double trueHeadingDeg,
                              @Param("altitudeFtMsl") double altitudeFtMsl,
                              @Param("indicatedAirspeedKt") double indicatedAirspeedKt,
                              @Param("isFake") boolean isFake,
                              @Param("icao24") String icao24,
                              @Param("plannedSquawk") String plannedSquawk,
                              @Param("ssrMode") String ssrMode,
                              @Param("targetAppearanceTime") BigDecimal targetAppearanceTime);

    @Select("SELECT " + AIRCRAFT_COLUMNS + " FROM exercise_aircraft WHERE id = #{id}")
    Map<String, Object> findById(@Param("id") String id);

    @Select("SELECT " + AIRCRAFT_COLUMNS + " FROM exercise_aircraft WHERE id = #{id} FOR UPDATE")
    Map<String, Object> lockById(@Param("id") String id);

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    java.time.LocalDateTime databaseNow();

    @Select("SELECT id FROM exercise_aircraft "
            + "WHERE exercise_group_id = #{groupId} AND active_callsign_key = #{callsign}")
    String findIdByGroupAndCallsign(@Param("groupId") String groupId,
                                     @Param("callsign") String callsign);

    @Select("SELECT COUNT(*) FROM exercise_aircraft "
            + "WHERE exercise_group_id = #{groupId} AND icao24 = #{icao24} "
            + "AND deleted_at IS NULL")
    int countActiveByIcao24(@Param("groupId") String groupId, @Param("icao24") String icao24);

    @Select("SELECT COUNT(*) FROM exercise_aircraft "
            + "WHERE exercise_group_id = #{groupId} AND current_squawk = #{squawk} "
            + "AND deleted_at IS NULL")
    int countActiveBySquawk(@Param("groupId") String groupId, @Param("squawk") String squawk);

    @Select("SELECT " + AIRCRAFT_COLUMNS + " FROM exercise_aircraft "
            + "WHERE exercise_group_id = #{groupId} AND deleted_at IS NULL ORDER BY callsign")
    List<Map<String, Object>> listByGroup(@Param("groupId") String groupId);

    @Update("UPDATE exercise_aircraft SET lifecycle = #{lifecycle}, "
            + "actual_appearance_time = #{actualAppearanceTime}, revision = revision + 1, "
            + "failure_code = NULL, failure_message = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND lifecycle = #{expected}")
    int transitionLifecycle(@Param("id") String id,
                            @Param("expected") String expected,
                            @Param("lifecycle") String lifecycle,
                            @Param("actualAppearanceTime") BigDecimal actualAppearanceTime);

    @Update("UPDATE exercise_aircraft SET lifecycle = #{lifecycle}, failure_code = #{failureCode}, "
            + "failure_message = #{failureMessage}, revision = revision + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND lifecycle = #{expected}")
    int markCreateFailed(@Param("id") String id,
                         @Param("expected") String expected,
                         @Param("lifecycle") String lifecycle,
                         @Param("failureCode") String failureCode,
                         @Param("failureMessage") String failureMessage);

    @Update("UPDATE exercise_aircraft SET lifecycle = 'DELETED', deleted_at = CURRENT_TIMESTAMP(3), "
            + "active_callsign_key = NULL, icao24 = NULL, revision = revision + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND lifecycle = #{expected}")
    int markDeleted(@Param("id") String id, @Param("expected") String expected);

    @Update("UPDATE exercise_aircraft SET latitude = #{latitude}, longitude = #{longitude}, "
            + "heading_degrees = #{trueHeadingDeg}, altitude_feet = #{altitudeFtMsl}, "
            + "speed_knots = #{indicatedAirspeedKt}, "
            + "vertical_speed_feet_per_minute = #{verticalRateFpm}, "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND lifecycle = 'ACTIVE'")
    int applyStateFrame(@Param("id") String id,
                        @Param("latitude") double latitude,
                        @Param("longitude") double longitude,
                        @Param("trueHeadingDeg") double trueHeadingDeg,
                        @Param("altitudeFtMsl") double altitudeFtMsl,
                        @Param("indicatedAirspeedKt") double indicatedAirspeedKt,
                        @Param("verticalRateFpm") double verticalRateFpm);

    @Update("UPDATE exercise_aircraft SET revision = revision + 1, "
            + "target_appearance_time = COALESCE(#{targetAppearanceTime}, target_appearance_time), "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND revision = #{expectedRevision}")
    int updatePlanRevision(@Param("id") String id,
                           @Param("expectedRevision") long expectedRevision,
                           @Param("targetAppearanceTime") BigDecimal targetAppearanceTime);

    @Select("SELECT COUNT(*) FROM workstation_terminal WHERE id = #{terminalId} "
            + "AND exercise_group_id = #{groupId} AND enabled = 1")
    int countEnabledTerminalInGroup(@Param("groupId") String groupId,
                                    @Param("terminalId") String terminalId);

    // ---- 出现调度（条件更新认领，详细设计 5.2.4）----

    @Select("SELECT a.id FROM exercise_aircraft a JOIN exercise_group g "
            + "ON g.id = a.exercise_group_id "
            + "WHERE a.exercise_group_id = #{groupId} AND g.state IN ('STARTING', 'RUNNING') "
            + "AND a.lifecycle = 'PLANNED' AND a.deleted_at IS NULL "
            + "AND a.target_appearance_time <= #{simulationTime} LIMIT 1")
    String findDuePlannedAircraft(@Param("groupId") String groupId,
                                  @Param("simulationTime") BigDecimal simulationTime);

    @Select("SELECT id FROM exercise_group WHERE state = 'RUNNING' ORDER BY id")
    List<String> listRunningGroupIds();

    @Select("SELECT COUNT(*) FROM exercise_aircraft WHERE exercise_group_id = #{groupId} "
            + "AND lifecycle = 'CREATE_REQUESTED' AND deleted_at IS NULL")
    int countCreateRequested(@Param("groupId") String groupId);

    @Update("UPDATE exercise_aircraft SET lifecycle = 'CREATE_REQUESTED', "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND lifecycle = 'PLANNED'")
    int claimDuePlan(@Param("id") String id);

    // ---- 责任分配（详细设计 5.3）----

    @Insert("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
            + "VALUES (#{id}, #{aircraftId}, #{terminalId}, #{aircraftId})")
    int insertAssignment(@Param("id") String id,
                         @Param("aircraftId") String aircraftId,
                         @Param("terminalId") String terminalId);

    @Select("SELECT id, aircraft_id, terminal_id, started_at, ended_at "
            + "FROM aircraft_assignment WHERE aircraft_id = #{aircraftId} "
            + "AND ended_at IS NULL LIMIT 1")
    Map<String, Object> findCurrentAssignment(@Param("aircraftId") String aircraftId);

    @Update("UPDATE aircraft_assignment SET ended_at = CURRENT_TIMESTAMP(3), current_key = NULL "
            + "WHERE id = #{id} AND ended_at IS NULL")
    int endAssignment(@Param("id") String assignmentId);

    @Select("SELECT COUNT(*) FROM aircraft_assignment "
            + "WHERE aircraft_id = #{aircraftId} AND ended_at IS NULL")
    int countCurrentAssignments(@Param("aircraftId") String aircraftId);

    @Select("SELECT state, simulation_time_seconds FROM exercise_group WHERE id = #{groupId}")
    Map<String, Object> findGroupStateAndTime(@Param("groupId") String groupId);

    // ---- 删除 Saga（评审 D2/P0-4）----

    /**
     * 删除状态迁移与责任席校验单语句原子完成（评审 D2）：
     * 无锁 findCurrentAssignment 后仅按 lifecycle CAS，移交并发下旧席仍会删除成功；
     * EXISTS 子查询保证只有当前开放分配席能推进生命周期。
     */
    @Update("UPDATE exercise_aircraft SET lifecycle = #{lifecycle}, revision = revision + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND lifecycle = #{expected} "
            + "AND EXISTS (SELECT 1 FROM aircraft_assignment a WHERE a.aircraft_id = #{id} "
            + "AND a.terminal_id = #{terminalId} AND a.current_key IS NOT NULL)")
    int transitionLifecycleAsResponsibleTerminal(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("lifecycle") String lifecycle,
            @Param("terminalId") String terminalId);

    /** 删除超时扫描（评审 P0-4：5 秒无法确认进 DELETE_FAILED）。 */
    @Select("SELECT id FROM exercise_aircraft WHERE lifecycle = 'DELETE_REQUESTED' "
            + "AND updated_at <= #{threshold}")
    List<String> findDeleteRequestedSince(@Param("threshold") java.time.LocalDateTime threshold);

    // ---- 纯业务字段指令写回（评审 P0-8；详细设计 7.6）----

    @Update("UPDATE exercise_aircraft SET current_squawk = #{currentSquawk}, "
            + "ssr_mode = #{ssrMode}, revision = revision + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND lifecycle = 'ACTIVE'")
    int updateTransponderState(@Param("id") String id,
                               @Param("currentSquawk") String currentSquawk,
                               @Param("ssrMode") String ssrMode);

    @Update("UPDATE exercise_aircraft SET transponder_ident_active = #{active}, "
            + "transponder_ident_expires_at = #{expiresAt}, revision = revision + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND lifecycle = 'ACTIVE'")
    int updateTransponderIdent(@Param("id") String id, @Param("active") boolean active,
                               @Param("expiresAt") BigDecimal expiresAt);

    /** 仿真时钟清除到期的 IDENT（详细设计 7.6：保持配置秒数后自动清除）。 */
    @Update("UPDATE exercise_aircraft SET transponder_ident_active = 0, "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE transponder_ident_active = 1 AND transponder_ident_expires_at <= "
            + "(SELECT g.simulation_time_seconds FROM exercise_group g "
            + "WHERE g.id = exercise_aircraft.exercise_group_id)")
    int expireDueTransponderIdent();

    /** DUPLICATE_SQUAWK 警告判定（详细设计 7.6）：同组其他活动航空器已用同一应答机编码。 */
    @Select("SELECT COUNT(*) FROM exercise_aircraft WHERE exercise_group_id = #{groupId} "
            + "AND lifecycle = 'ACTIVE' AND deleted_at IS NULL "
            + "AND current_squawk = #{squawk} AND id <> #{excludingAircraftId}")
    int countActiveSquawkInGroup(@Param("groupId") String groupId,
                                 @Param("squawk") String squawk,
                                 @Param("excludingAircraftId") String excludingAircraftId);

    /** P_LEVEL/P_TIME 冲突键 legId 解析（评审 P0-11）：当前计划版本中该代码点的稳定航段 ID。 */
    @Select("SELECT l.id FROM flight_plan_leg l JOIN flight_plan p ON p.id = l.flight_plan_id "
            + "WHERE p.aircraft_id = #{aircraftId} AND l.point_code = #{pointCode} "
            + "ORDER BY p.plan_version DESC, l.sequence_number LIMIT 1")
    String findCurrentLegIdByPoint(@Param("aircraftId") String aircraftId,
                                   @Param("pointCode") String pointCode);
}
