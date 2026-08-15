package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface InstructionMapper {
    @Select("SELECT COALESCE(MAX(sequence_number), 0) FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId}")
    long maxSequence(String aircraftId);

    @Select("SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND status = 'EXECUTING'")
    int executingCount(String aircraftId, String channel);

    @Insert("INSERT INTO aircraft_instruction (id, exercise_aircraft_id, raw_text, instruction_type, control_channel, insertion_mode, status, sequence_number, parsed_payload, failure_code, failure_message, started_at) VALUES (#{id}, #{exerciseAircraftId}, #{rawText}, #{instructionType}, #{controlChannel}, #{insertionMode}, #{status}, #{sequenceNumber}, #{parsedPayload}, #{failureCode}, #{failureMessage}, CURRENT_TIMESTAMP)")
    int insert(InstructionRow row);

    @Update("UPDATE exercise_aircraft SET active_instruction_text = #{text}, updated_at = CURRENT_TIMESTAMP WHERE id = #{aircraftId}")
    int updateActiveInstruction(String aircraftId, String text);

    @Select("SELECT id, exercise_aircraft_id, raw_text, instruction_type, control_channel, insertion_mode, status, sequence_number, parsed_payload, failure_code, failure_message FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} ORDER BY sequence_number, created_at")
    List<InstructionRow> findAll(String aircraftId);

    @Select("SELECT id, exercise_aircraft_id, raw_text, instruction_type, control_channel, insertion_mode, status, sequence_number, parsed_payload, failure_code, failure_message FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND status = 'EXECUTING' ORDER BY sequence_number LIMIT 1")
    InstructionRow findExecuting(String aircraftId, String channel);

    @Select("SELECT id, exercise_aircraft_id, raw_text, instruction_type, control_channel, insertion_mode, status, sequence_number, parsed_payload, failure_code, failure_message FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} AND status = 'EXECUTING' ORDER BY started_at DESC, created_at DESC LIMIT 1")
    InstructionRow findLatestExecuting(String aircraftId);

    @Update("UPDATE aircraft_instruction SET sequence_number = sequence_number + 1 WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND sequence_number >= #{fromSequence}")
    int shiftFrom(String aircraftId, String channel, long fromSequence);

    @Select("SELECT id, exercise_aircraft_id, raw_text, instruction_type, control_channel, insertion_mode, status, sequence_number, parsed_payload, failure_code, failure_message FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND status = 'PENDING' ORDER BY sequence_number LIMIT 1")
    InstructionRow findNextPending(String aircraftId, String channel);

    @Select("SELECT id, exercise_aircraft_id, raw_text, instruction_type, control_channel, insertion_mode, status, sequence_number, parsed_payload, failure_code, failure_message FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND status = 'PENDING' ORDER BY sequence_number")
    List<InstructionRow> findPending(String aircraftId, String channel);

    @Update("UPDATE aircraft_instruction SET status = #{status}, completed_at = CASE WHEN #{status} = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE completed_at END, started_at = CASE WHEN #{status} = 'EXECUTING' THEN CURRENT_TIMESTAMP ELSE started_at END WHERE id = #{id}")
    int updateStatus(String id, String status);

    @Update("UPDATE aircraft_instruction SET status = 'FAILED', failure_code = #{code}, failure_message = #{message}, completed_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int markFailed(String id, String code, String message);

    @Update("UPDATE aircraft_instruction SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND status = 'EXECUTING'")
    int cancelExecuting(String aircraftId, String channel);

    @Update("UPDATE aircraft_instruction SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP WHERE exercise_aircraft_id = #{aircraftId} AND control_channel = #{channel} AND status = 'PENDING'")
    int cancelPending(String aircraftId, String channel);
}
