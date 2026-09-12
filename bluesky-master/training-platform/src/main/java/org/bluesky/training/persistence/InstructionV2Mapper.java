package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** P09：指令内核持久化（V11：v2 指令列 + blocker/guidance/composite/dispatch_slot）。 */
public interface InstructionV2Mapper {
    @Select("SELECT CURRENT_TIMESTAMP(3)")
    java.time.LocalDateTime databaseNow();

    @Select("SELECT i.id, i.parsed_payload, g.simulation_time_seconds FROM aircraft_instruction i JOIN exercise_group g ON g.id=i.exercise_group_id WHERE i.status='BLOCKED' AND g.state='RUNNING' AND EXISTS (SELECT 1 FROM instruction_blocker b WHERE b.instruction_id=i.id AND b.reason='SCHEDULED_TIME_NOT_REACHED' AND b.released_at IS NULL)")
    List<Map<String,Object>> scheduledInstructions();

    @Select("SELECT id FROM aircraft_instruction WHERE exercise_aircraft_id=#{aircraftId} AND status IN ('RECEIVED','VALIDATED','BLOCKED','DISPATCHING','EXECUTING')")
    List<String> activeIdsByAircraft(@Param("aircraftId") String aircraftId);

    String V2_COLUMNS = "id, exercise_aircraft_id, exercise_group_id, source_terminal_id, "
            + "instruction_type, control_channel, conflict_key, scheduling, status, "
            + "sequence_number, predecessor_id, parent_instruction_id, execution_phase, "
            + "failure_code, failure_message, raw_text, parsed_payload, revision";

    @Insert("INSERT INTO aircraft_instruction (id, exercise_aircraft_id, exercise_group_id, "
            + "source_terminal_id, instruction_type, control_channel, conflict_key, scheduling, "
            + "status, sequence_number, predecessor_id, raw_text, parsed_payload, "
            + "idempotency_key, insertion_mode, idempotency_request_digest) "
            + "VALUES (#{id}, #{aircraftId}, #{groupId}, #{sourceTerminalId}, #{type}, "
            + "#{controlChannel}, #{conflictKey}, #{scheduling}, #{status}, #{sequenceNumber}, "
            + "#{predecessorId}, #{rawText}, #{parsedPayload}, #{idempotencyKey}, #{scheduling}, "
            + "#{idempotencyRequestDigest})")
    int insertInstruction(@Param("id") String id,
                          @Param("aircraftId") String aircraftId,
                          @Param("groupId") String groupId,
                          @Param("sourceTerminalId") String sourceTerminalId,
                          @Param("type") String type,
                          @Param("controlChannel") String controlChannel,
                          @Param("conflictKey") String conflictKey,
                          @Param("scheduling") String scheduling,
                          @Param("status") String status,
                          @Param("sequenceNumber") long sequenceNumber,
                          @Param("predecessorId") String predecessorId,
                          @Param("rawText") String rawText,
                          @Param("parsedPayload") String parsedPayload,
                          @Param("idempotencyKey") String idempotencyKey,
                          @Param("idempotencyRequestDigest") String idempotencyRequestDigest);

    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction WHERE id = #{id}")
    Map<String, Object> findById(@Param("id") String id);

    @Select("SELECT id FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} "
            + "AND idempotency_key = #{idempotencyKey}")
    String findByIdempotencyKey(@Param("aircraftId") String aircraftId,
                                 @Param("idempotencyKey") String idempotencyKey);

    /** 同键异摘要判定（详细设计 9.1）：回放前必须比对请求摘要。 */
    @Select("SELECT idempotency_request_digest FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND idempotency_key = #{idempotencyKey}")
    String findDigestByIdempotencyKey(@Param("aircraftId") String aircraftId,
                                      @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} ORDER BY created_at DESC, id "
            + "LIMIT #{limit}")
    List<Map<String, Object>> listByAircraft(@Param("aircraftId") String aircraftId,
                                             @Param("limit") int limit);

    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND conflict_key = #{conflictKey} "
            + "ORDER BY sequence_number DESC LIMIT 1")
    Map<String, Object> findLatestByConflictKey(@Param("aircraftId") String aircraftId,
                                                 @Param("conflictKey") String conflictKey);

    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND conflict_key = #{conflictKey} "
            + "AND status IN ('RECEIVED','VALIDATED','BLOCKED','DISPATCHING') "
            + "ORDER BY sequence_number")
    List<Map<String, Object>> findWaitingByConflictKey(@Param("aircraftId") String aircraftId,
                                                        @Param("conflictKey") String conflictKey);

    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE predecessor_id = #{predecessorId} AND status IN "
            + "('RECEIVED','VALIDATED','BLOCKED') ORDER BY sequence_number")
    List<Map<String, Object>> listSuccessorsOf(@Param("predecessorId") String predecessorId);

    /** 迁移桥：取在途下发中的指令（超窗判定由看门狗负责）。 */
    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE status = 'DISPATCHING' ORDER BY created_at LIMIT #{limit}")
    List<Map<String, Object>> findDispatchingInstructions(@Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(sequence_number), 0) FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND conflict_key = #{conflictKey}")
    long maxSequence(@Param("aircraftId") String aircraftId,
                     @Param("conflictKey") String conflictKey);

    @Update("UPDATE aircraft_instruction SET status = #{status}, revision = revision + 1 "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int transitionStatus(@Param("id") String id,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("status") String status);

    @Update("UPDATE aircraft_instruction SET status = #{status}, failure_code = #{failureCode}, "
            + "failure_message = #{failureMessage}, revision = revision + 1 "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int terminateWithReason(@Param("id") String id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("status") String status,
                            @Param("failureCode") String failureCode,
                            @Param("failureMessage") String failureMessage);

    // ---- blocker ----

    @Insert("INSERT INTO instruction_blocker (id, instruction_id, reason) "
            + "VALUES (#{id}, #{instructionId}, #{reason})")
    int insertBlocker(@Param("id") String id,
                      @Param("instructionId") String instructionId,
                      @Param("reason") String reason);

    @Update("UPDATE instruction_blocker SET released_at = CURRENT_TIMESTAMP(3) "
            + "WHERE instruction_id = #{instructionId} AND reason = #{reason} "
            + "AND released_at IS NULL")
    int releaseBlocker(@Param("instructionId") String instructionId,
                       @Param("reason") String reason);

    @Select("SELECT reason FROM instruction_blocker WHERE instruction_id = #{instructionId} "
            + "AND released_at IS NULL")
    List<String> activeBlockers(@Param("instructionId") String instructionId);

    // ---- guidance target ----

    @Insert("INSERT INTO guidance_target (id, instruction_id, aircraft_id, channel, state, "
            + "target_json) VALUES (#{id}, #{instructionId}, #{aircraftId}, #{channel}, "
            + "'PENDING_APPLY', #{targetJson})")
    int insertGuidanceTarget(@Param("id") String id,
                             @Param("instructionId") String instructionId,
                             @Param("aircraftId") String aircraftId,
                             @Param("channel") String channel,
                             @Param("targetJson") String targetJson);

    @Update("UPDATE guidance_target SET state = #{state}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND state = #{expectedState}")
    int transitionGuidance(@Param("id") String id,
                           @Param("expectedState") String expectedState,
                           @Param("state") String state);

    @Update("UPDATE guidance_target SET state = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE aircraft_id = #{aircraftId} AND channel = #{channel} AND state = 'ACTIVE'")
    int supersedeActiveGuidance(@Param("aircraftId") String aircraftId,
                                @Param("channel") String channel);

    /** 引导目标随指令收敛正常清除（详细设计 6.3：COMPLETED → CLEARED）。 */
    @Update("UPDATE guidance_target SET state = 'CLEARED', updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE aircraft_id = #{aircraftId} AND channel = #{channel} AND state = 'ACTIVE'")
    int clearActiveGuidance(@Param("aircraftId") String aircraftId,
                            @Param("channel") String channel);

    @Select("SELECT id, instruction_id, aircraft_id, channel, state FROM guidance_target "
            + "WHERE instruction_id = #{instructionId}")
    List<Map<String, Object>> guidanceOf(@Param("instructionId") String instructionId);

    @Select("SELECT id, state FROM guidance_target WHERE aircraft_id = #{aircraftId} "
            + "AND channel = #{channel} AND state = 'ACTIVE' LIMIT 1")
    Map<String, Object> findActiveGuidance(@Param("aircraftId") String aircraftId,
                                           @Param("channel") String channel);

    // ---- 删除 Saga 收尾（评审 D1/D12）----

    /** 删除确认后同事务取消该航空器全部非终态指令。 */
    @Update("UPDATE aircraft_instruction SET status = 'CANCELLED', "
            + "failure_code = 'AIRCRAFT_DELETED', "
            + "failure_message = '航空器已删除，指令随之中止', revision = revision + 1 "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND status IN "
            + "('RECEIVED', 'VALIDATED', 'BLOCKED', 'DISPATCHING', 'EXECUTING')")
    int terminateActiveByAircraft(@Param("aircraftId") String aircraftId);

    /** 删除确认后同事务清除该航空器全部活动引导目标。 */
    @Update("UPDATE guidance_target SET state = 'SUPERSEDED', "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE aircraft_id = #{aircraftId} "
            + "AND state = 'ACTIVE'")
    int supersedeAllActiveGuidance(@Param("aircraftId") String aircraftId);

    /** 删除预览的活动指令数（评审 D12：真实计数替代硬编码 0）。 */
    @Select("SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = "
            + "#{aircraftId} AND status IN ('RECEIVED', 'VALIDATED', 'BLOCKED', "
            + "'DISPATCHING', 'EXECUTING')")
    int countActiveByAircraft(@Param("aircraftId") String aircraftId);

    // ---- composite ----

    @Insert("INSERT INTO composite_instruction_child (id, parent_id, child_id, channel, required) "
            + "VALUES (#{id}, #{parentId}, #{childId}, #{channel}, #{required})")
    int insertCompositeChild(@Param("id") String id,
                             @Param("parentId") String parentId,
                             @Param("childId") String childId,
                             @Param("channel") String channel,
                             @Param("required") boolean required);

    @Select("SELECT child_id AS \"childId\", channel, required FROM composite_instruction_child "
            + "WHERE parent_id = #{parentId}")
    List<Map<String, Object>> childrenOf(@Param("parentId") String parentId);

    /** 复合父项反查（子项行不落 parent_instruction_id，父子关系只在链接表）。 */
    @Select("SELECT parent_id FROM composite_instruction_child WHERE child_id = #{childId} "
            + "LIMIT 1")
    String findParentIdOf(@Param("childId") String childId);

    // ---- dispatch slot ----

    @Insert("INSERT INTO dispatch_slot (aircraft_id, conflict_key, instruction_id) "
            + "VALUES (#{aircraftId}, #{conflictKey}, #{instructionId})")
    int claimSlot(@Param("aircraftId") String aircraftId,
                  @Param("conflictKey") String conflictKey,
                  @Param("instructionId") String instructionId);

    @Delete("DELETE FROM dispatch_slot WHERE aircraft_id = #{aircraftId} "
            + "AND conflict_key = #{conflictKey} AND instruction_id = #{instructionId}")
    int releaseSlot(@Param("aircraftId") String aircraftId,
                    @Param("conflictKey") String conflictKey,
                    @Param("instructionId") String instructionId);

    @Select("SELECT instruction_id FROM dispatch_slot WHERE aircraft_id = #{aircraftId} "
            + "AND conflict_key = #{conflictKey}")
    String slotHolder(@Param("aircraftId") String aircraftId,
                      @Param("conflictKey") String conflictKey);

    // ---- 派发链路（评审 P0-6/P0-7/P0-9）----

    /** 5 秒技术确认窗口扫描（评审 P0-6/E12：截止时刻落库，重启不丢失）；
     *  兜底：截止缺失（历史数据/登记失败）时按创建时间宽限超时。 */
    @Select("SELECT id FROM aircraft_instruction WHERE status = 'DISPATCHING' AND ("
            + "(dispatch_deadline_at IS NOT NULL AND dispatch_deadline_at <= #{now}) "
            + "OR (dispatch_deadline_at IS NULL AND created_at <= #{grace}))")
    List<String> findDispatchTimedOut(@Param("now") java.time.LocalDateTime now,
                                      @Param("grace") java.time.LocalDateTime grace);

    /** 派发起始时登记技术确认截止时刻（详细设计 6.3.5）。 */
    @Update("UPDATE aircraft_instruction SET dispatch_deadline_at = #{deadline} "
            + "WHERE id = #{id} AND status = 'DISPATCHING'")
    int markDispatchDeadline(@Param("id") String id,
                             @Param("deadline") java.time.LocalDateTime deadline);

    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND status = 'EXECUTING' "
            + "ORDER BY sequence_number")
    List<Map<String, Object>> findExecutingByAircraft(@Param("aircraftId") String aircraftId);

    /** 帧事件入报告需在指令全部终态后继续（LANDED 发生在 ILS 收敛之后）。 */
    @Select("SELECT exercise_group_id FROM exercise_aircraft WHERE id = #{aircraftId}")
    String findGroupIdOfAircraft(@Param("aircraftId") String aircraftId);

    /** 同冲突键当前 EXECUTING 指令（REPLACE 生效时旧目标 → REPLACED，评审 P0-7）。 */
    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} AND conflict_key = #{conflictKey} "
            + "AND status = 'EXECUTING' AND id <> #{excludingId} ORDER BY sequence_number")
    List<Map<String, Object>> findExecutingByConflictKey(@Param("aircraftId") String aircraftId,
            @Param("conflictKey") String conflictKey,
            @Param("excludingId") String excludingId);

    /** 工作台 CANCEL 文本命令目标：该机最新的非终态指令（含 BLOCKED/DISPATCHING）。 */
    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE exercise_aircraft_id = #{aircraftId} "
            + "AND status IN ('RECEIVED','VALIDATED','BLOCKED','DISPATCHING','EXECUTING') "
            + "ORDER BY sequence_number DESC, created_at DESC LIMIT 1")
    Map<String, Object> findLatestActiveByAircraft(@Param("aircraftId") String aircraftId);

    /** 阻塞原因批量释放扫描（评审 P0-9：TRAINING_PAUSED 恢复批量清除）。 */
    @Select("SELECT DISTINCT b.instruction_id FROM instruction_blocker b "
            + "JOIN aircraft_instruction i ON i.id = b.instruction_id "
            + "WHERE b.reason = #{reason} AND b.released_at IS NULL "
            + "AND i.exercise_group_id = #{groupId}")
    List<String> findBlockedIdsByReasonInGroup(@Param("groupId") String groupId,
                                               @Param("reason") String reason);

    /** 后继取消查询修正（评审 E18：DISPATCHING/EXECUTING 也必须被取消）。 */
    @Select("SELECT " + V2_COLUMNS + " FROM aircraft_instruction "
            + "WHERE predecessor_id = #{predecessorId} AND status IN "
            + "('RECEIVED','VALIDATED','BLOCKED','DISPATCHING','EXECUTING') "
            + "ORDER BY sequence_number")
    List<Map<String, Object>> listActiveSuccessorsOf(@Param("predecessorId") String predecessorId);
}
