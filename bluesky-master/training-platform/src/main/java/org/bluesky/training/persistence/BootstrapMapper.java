package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface BootstrapMapper {
    @Select("SELECT id, name, state, simulation_time_seconds FROM exercise_group WHERE id = 'GROUP-DEFAULT'")
    ExerciseGroupRow findDefaultGroup();

    @Select("SELECT id, name FROM workstation_terminal WHERE id = 'PP-DEFAULT'")
    TerminalRow findDefaultTerminal();

    @Select("SELECT parameter_key, parameter_value FROM system_parameter WHERE parameter_key LIKE 'ui.%'")
    List<Map<String, String>> findUiParameters();

    @Delete("DELETE FROM aircraft_instruction")
    int deleteInstructions();

    @Delete("DELETE FROM exercise_aircraft")
    int deleteAircraft();

    @Update("UPDATE exercise_group SET state = 'READY', simulation_time_seconds = 0, updated_at = CURRENT_TIMESTAMP WHERE id = 'GROUP-DEFAULT'")
    int resetDefaultGroup();

    @Update("UPDATE exercise_group SET state = #{state}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND state = #{expectedState}")
    int transitionGroupState(String id, String expectedState, String state);

    @Update("UPDATE exercise_group SET simulation_time_seconds = #{simulationTimeSeconds}, updated_at = CURRENT_TIMESTAMP WHERE id = 'GROUP-DEFAULT'")
    int updateSimulationTime(long simulationTimeSeconds);
}
