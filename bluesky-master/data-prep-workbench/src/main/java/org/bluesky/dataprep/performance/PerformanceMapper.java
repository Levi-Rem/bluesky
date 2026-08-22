package org.bluesky.dataprep.performance;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PerformanceMapper {
    String COLS = "p.id, p.aircraft_id, t.code, t.name, t.manufacturer, t.model_name, t.engine_type, "
            + "t.icao_wake_category, t.reacat_wake_category, t.maximum_takeoff_weight_kg, "
            + "t.performance_category, t.status, p.sequence_no, p.altitude_layer, "
            + "p.holding_speed_low, p.holding_speed_middle, p.holding_speed_high, p.takeoff_speed, "
            + "p.takeoff_duration_s, p.takeoff_altitude_ft, p.takeoff_distance_nm, p.landing_speed, "
            + "p.radar_cross_section, p.maximum_speed, p.maximum_altitude_layer, p.maximum_turn, "
            + "p.mach_capable, p.jet_aircraft, p.standard_turn, p.turn_response_1, p.turn_response_2, "
            + "p.turn_response_3, p.acceleration_response_1, p.acceleration_response_2, "
            + "p.acceleration_response_3, p.deceleration_response_1, p.deceleration_response_2, "
            + "p.deceleration_response_3, p.climb_response_1, p.climb_response_2, p.climb_response_3, "
            + "p.descent_response_1, p.descent_response_2, p.descent_response_3, p.climb_rate_ft_min, "
            + "p.descent_rate_ft_min, p.acceleration_kts_min, p.deceleration_kts_min, p.cruise_speed, "
            + "p.stall_speed, p.climb_speed, p.descent_speed, p.revision, p.created_at, p.created_by, "
            + "p.updated_at, p.updated_by ";

    String FROM = "FROM aircraft_performance p JOIN aircraft_type t ON t.id = p.aircraft_id "
            + "WHERE p.deleted = FALSE AND t.deleted = FALSE ";

    @Select("SELECT " + COLS + FROM
            + "ORDER BY t.code, t.icao_wake_category, t.reacat_wake_category, p.sequence_no "
            + "LIMIT #{size} OFFSET #{offset}")
    List<PerformanceRow> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) " + FROM)
    long count();

    @Select("SELECT " + COLS + FROM + "AND p.id = #{id}")
    PerformanceRow findById(String id);

    @Select("SELECT id FROM aircraft_type WHERE deleted = FALSE AND code = #{code} "
            + "AND COALESCE(icao_wake_category, '') = COALESCE(#{icaoWakeCategory}, '') "
            + "AND COALESCE(reacat_wake_category, '') = COALESCE(#{reacatWakeCategory}, '') LIMIT 1")
    String findAircraftId(PerformanceRow row);

    @Select("SELECT COALESCE(MAX(sequence_no), -1) + 1 FROM aircraft_performance "
            + "WHERE aircraft_id = #{aircraftId} AND deleted = FALSE")
    int nextSequence(String aircraftId);

    @Select("SELECT COUNT(*) FROM aircraft_performance WHERE aircraft_id = #{aircraftId} "
            + "AND altitude_layer = #{altitudeLayer} AND deleted = FALSE AND id <> #{excludeId}")
    int countLayer(@Param("aircraftId") String aircraftId,
                   @Param("altitudeLayer") String altitudeLayer,
                   @Param("excludeId") String excludeId);

    @Insert("INSERT INTO aircraft_type (id, code, name, manufacturer, model_name, engine_type, "
            + "icao_wake_category, reacat_wake_category, maximum_takeoff_weight_kg, performance_category, "
            + "status, revision, deleted, created_by, updated_by) VALUES "
            + "(#{aircraftId}, #{code}, #{name}, #{manufacturer}, #{modelName}, #{engineType}, "
            + "#{icaoWakeCategory}, #{reacatWakeCategory}, #{maximumTakeoffWeightKg}, #{performanceCategory}, "
            + "#{status}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insertAircraft(PerformanceRow row);

    @Insert("INSERT INTO aircraft_performance (id, aircraft_id, sequence_no, altitude_layer, "
            + "holding_speed_low, holding_speed_middle, holding_speed_high, takeoff_speed, takeoff_duration_s, "
            + "takeoff_altitude_ft, takeoff_distance_nm, landing_speed, radar_cross_section, maximum_speed, "
            + "maximum_altitude_layer, maximum_turn, mach_capable, jet_aircraft, standard_turn, "
            + "turn_response_1, turn_response_2, turn_response_3, acceleration_response_1, "
            + "acceleration_response_2, acceleration_response_3, deceleration_response_1, "
            + "deceleration_response_2, deceleration_response_3, climb_response_1, climb_response_2, "
            + "climb_response_3, descent_response_1, descent_response_2, descent_response_3, "
            + "climb_rate_ft_min, descent_rate_ft_min, acceleration_kts_min, deceleration_kts_min, "
            + "cruise_speed, stall_speed, climb_speed, descent_speed, revision, deleted, created_by, updated_by) "
            + "VALUES (#{id}, #{aircraftId}, #{sequenceNo}, #{altitudeLayer}, #{holdingSpeedLow}, "
            + "#{holdingSpeedMiddle}, #{holdingSpeedHigh}, #{takeoffSpeed}, #{takeoffDurationS}, "
            + "#{takeoffAltitudeFt}, #{takeoffDistanceNm}, #{landingSpeed}, #{radarCrossSection}, "
            + "#{maximumSpeed}, #{maximumAltitudeLayer}, #{maximumTurn}, #{machCapable}, #{jetAircraft}, "
            + "#{standardTurn}, #{turnResponse1}, #{turnResponse2}, #{turnResponse3}, "
            + "#{accelerationResponse1}, #{accelerationResponse2}, #{accelerationResponse3}, "
            + "#{decelerationResponse1}, #{decelerationResponse2}, #{decelerationResponse3}, "
            + "#{climbResponse1}, #{climbResponse2}, #{climbResponse3}, #{descentResponse1}, "
            + "#{descentResponse2}, #{descentResponse3}, #{climbRateFtMin}, #{descentRateFtMin}, "
            + "#{accelerationKtsMin}, #{decelerationKtsMin}, #{cruiseSpeed}, #{stallSpeed}, #{climbSpeed}, "
            + "#{descentSpeed}, 0, FALSE, #{createdBy}, #{updatedBy})")
    int insertPerformance(PerformanceRow row);

    @Update("UPDATE aircraft_type SET code=#{code}, name=#{name}, manufacturer=#{manufacturer}, "
            + "model_name=#{modelName}, engine_type=#{engineType}, icao_wake_category=#{icaoWakeCategory}, "
            + "reacat_wake_category=#{reacatWakeCategory}, maximum_takeoff_weight_kg=#{maximumTakeoffWeightKg}, "
            + "performance_category=#{performanceCategory}, updated_by=#{updatedBy}, "
            + "updated_at=CURRENT_TIMESTAMP(3), "
            + "revision=revision+1 WHERE id=#{aircraftId} AND deleted=FALSE")
    int updateAircraft(PerformanceRow row);

    @Update("UPDATE aircraft_performance SET altitude_layer=#{altitudeLayer}, "
            + "holding_speed_low=#{holdingSpeedLow}, holding_speed_middle=#{holdingSpeedMiddle}, "
            + "holding_speed_high=#{holdingSpeedHigh}, takeoff_speed=#{takeoffSpeed}, "
            + "takeoff_duration_s=#{takeoffDurationS}, takeoff_altitude_ft=#{takeoffAltitudeFt}, "
            + "takeoff_distance_nm=#{takeoffDistanceNm}, landing_speed=#{landingSpeed}, "
            + "radar_cross_section=#{radarCrossSection}, maximum_speed=#{maximumSpeed}, "
            + "maximum_altitude_layer=#{maximumAltitudeLayer}, maximum_turn=#{maximumTurn}, "
            + "mach_capable=#{machCapable}, jet_aircraft=#{jetAircraft}, standard_turn=#{standardTurn}, "
            + "turn_response_1=#{turnResponse1}, turn_response_2=#{turnResponse2}, "
            + "turn_response_3=#{turnResponse3}, acceleration_response_1=#{accelerationResponse1}, "
            + "acceleration_response_2=#{accelerationResponse2}, acceleration_response_3=#{accelerationResponse3}, "
            + "deceleration_response_1=#{decelerationResponse1}, deceleration_response_2=#{decelerationResponse2}, "
            + "deceleration_response_3=#{decelerationResponse3}, climb_response_1=#{climbResponse1}, "
            + "climb_response_2=#{climbResponse2}, climb_response_3=#{climbResponse3}, "
            + "descent_response_1=#{descentResponse1}, descent_response_2=#{descentResponse2}, "
            + "descent_response_3=#{descentResponse3}, "
            + "climb_rate_ft_min=#{climbRateFtMin}, descent_rate_ft_min=#{descentRateFtMin}, "
            + "acceleration_kts_min=#{accelerationKtsMin}, deceleration_kts_min=#{decelerationKtsMin}, "
            + "cruise_speed=#{cruiseSpeed}, stall_speed=#{stallSpeed}, climb_speed=#{climbSpeed}, "
            + "descent_speed=#{descentSpeed}, updated_by=#{updatedBy}, revision=revision+1 "
            + "WHERE id=#{id} AND revision=#{revision} AND deleted=FALSE")
    int updateLayer(PerformanceRow row);

    @Update("UPDATE aircraft_performance SET revision=revision+1, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE id=#{id} AND revision=#{revision} AND deleted=FALSE")
    int touchLayerRevision(@Param("id") String id, @Param("revision") int revision);

    @Update("UPDATE aircraft_type SET status=#{status}, revision=revision+1, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE id=#{aircraftId} AND deleted=FALSE")
    int updateAircraftStatus(@Param("aircraftId") String aircraftId, @Param("status") String status);

    @Update("UPDATE aircraft_performance SET deleted=TRUE, updated_at=CURRENT_TIMESTAMP(3), revision=revision+1 "
            + "WHERE id=#{id} AND revision=#{revision} AND deleted=FALSE")
    int markDeleted(@Param("id") String id, @Param("revision") int revision);

    @Select("SELECT COUNT(*) FROM aircraft_performance WHERE aircraft_id=#{aircraftId} AND deleted=FALSE")
    int countAircraftLayers(String aircraftId);

    @Update("UPDATE aircraft_type SET deleted=TRUE, updated_at=CURRENT_TIMESTAMP(3), revision=revision+1 WHERE id=#{aircraftId} AND deleted=FALSE")
    int markAircraftDeleted(String aircraftId);

}
