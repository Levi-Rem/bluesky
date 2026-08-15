package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AircraftMapper {
    @Insert("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, callsign, aircraft_type, wake_category, transponder_code, origin, destination, appearance_offset_minutes, latitude, longitude, initial_waypoint, heading_degrees, altitude_feet, speed_knots, vertical_speed_feet_per_minute, route_text) "
            + "VALUES (#{id}, 'GROUP-DEFAULT', #{assignedTerminalId}, #{callsign}, #{aircraftType}, #{wakeCategory}, #{transponderCode}, #{origin}, #{destination}, #{appearanceOffsetMinutes}, #{latitude}, #{longitude}, #{initialWaypoint}, #{headingDegrees}, #{altitudeFeet}, #{speedKnots}, 0, #{routeText})")
    int insert(AircraftRow row);

    @Select("SELECT id, assigned_terminal_id, callsign, aircraft_type, wake_category, transponder_code, origin, destination, appearance_offset_minutes, latitude, longitude, initial_waypoint, heading_degrees, altitude_feet, speed_knots, vertical_speed_feet_per_minute, route_text, active_instruction_text FROM exercise_aircraft WHERE exercise_group_id = 'GROUP-DEFAULT' ORDER BY callsign")
    List<AircraftRow> findAllDefaultGroup();

    @Select("SELECT id, assigned_terminal_id, callsign, aircraft_type, wake_category, transponder_code, origin, destination, appearance_offset_minutes, latitude, longitude, initial_waypoint, heading_degrees, altitude_feet, speed_knots, vertical_speed_feet_per_minute, route_text, active_instruction_text FROM exercise_aircraft WHERE id = #{id}")
    AircraftRow findById(String id);

    @Delete("DELETE FROM aircraft_instruction WHERE exercise_aircraft_id = #{aircraftId}")
    int deleteInstructions(String aircraftId);

    @Delete("DELETE FROM exercise_aircraft WHERE id = #{id}")
    int deleteById(String id);

    @Update("UPDATE exercise_aircraft SET latitude = #{latitude}, longitude = #{longitude}, heading_degrees = #{headingDegrees}, altitude_feet = #{altitudeFeet}, speed_knots = #{speedKnots}, vertical_speed_feet_per_minute = #{verticalSpeedFeetPerMinute}, route_text = #{routeText}, updated_at = CURRENT_TIMESTAMP WHERE callsign = #{callsign} AND exercise_group_id = 'GROUP-DEFAULT'")
    int updateActualState(AircraftRow row);

    @Select("SELECT id, assigned_terminal_id, callsign, aircraft_type, wake_category, transponder_code, origin, destination, appearance_offset_minutes, latitude, longitude, initial_waypoint, heading_degrees, altitude_feet, speed_knots, vertical_speed_feet_per_minute, route_text, active_instruction_text FROM exercise_aircraft WHERE callsign = #{callsign} AND exercise_group_id = 'GROUP-DEFAULT'")
    AircraftRow findByCallsign(String callsign);
}
