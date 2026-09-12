package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** P07：版本化飞行计划持久化（V10 flight_plan / flight_plan_leg）。 */
public interface FlightPlanMapper {

    @Insert("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
            + "planned_squawk, ssr_mode, cruise_altitude_ft_msl, "
            + "cruise_indicated_airspeed_kt, route_text) "
            + "VALUES (#{id}, #{aircraftId}, #{planVersion}, #{origin}, #{destination}, "
            + "#{plannedSquawk}, #{ssrMode}, #{cruiseAltitudeFtMsl}, "
            + "#{cruiseIndicatedAirspeedKt}, #{routeText})")
    int insertPlan(@Param("id") String id,
                   @Param("aircraftId") String aircraftId,
                   @Param("planVersion") int planVersion,
                   @Param("origin") String origin,
                   @Param("destination") String destination,
                   @Param("plannedSquawk") String plannedSquawk,
                   @Param("ssrMode") String ssrMode,
                   @Param("cruiseAltitudeFtMsl") Integer cruiseAltitudeFtMsl,
                   @Param("cruiseIndicatedAirspeedKt") Integer cruiseIndicatedAirspeedKt,
                   @Param("routeText") String routeText);

    @Insert("INSERT INTO flight_plan_leg (id, flight_plan_id, sequence_number, nav_point_id, "
            + "point_code, latitude_deg, longitude_deg, fly_over) "
            + "VALUES (#{id}, #{flightPlanId}, #{sequenceNumber}, #{navPointId}, "
            + "#{pointCode}, #{latitudeDeg}, #{longitudeDeg}, #{flyOver})")
    int insertLeg(@Param("id") String id,
                  @Param("flightPlanId") String flightPlanId,
                  @Param("sequenceNumber") int sequenceNumber,
                  @Param("navPointId") String navPointId,
                  @Param("pointCode") String pointCode,
                  @Param("latitudeDeg") java.math.BigDecimal latitudeDeg,
                  @Param("longitudeDeg") java.math.BigDecimal longitudeDeg,
                  @Param("flyOver") boolean flyOver);

    @Select("SELECT COALESCE(MAX(plan_version), 0) FROM flight_plan WHERE aircraft_id = #{aircraftId}")
    int maxVersion(@Param("aircraftId") String aircraftId);

    @Select("SELECT id FROM flight_plan WHERE aircraft_id = #{aircraftId} "
            + "AND plan_version = #{planVersion}")
    String findPlanId(@Param("aircraftId") String aircraftId,
                      @Param("planVersion") int planVersion);

    @Select("SELECT id, aircraft_id AS \"aircraftId\", plan_version AS \"planVersion\", "
            + "origin, destination, planned_squawk AS \"plannedSquawk\", ssr_mode AS \"ssrMode\", "
            + "cruise_altitude_ft_msl AS \"cruiseAltitudeFtMsl\", "
            + "cruise_indicated_airspeed_kt AS \"cruiseIndicatedAirspeedKt\", "
            + "route_text AS \"routeText\", created_at AS \"createdAt\" "
            + "FROM flight_plan WHERE aircraft_id = #{aircraftId} "
            + "ORDER BY plan_version DESC")
    List<Map<String, Object>> listVersions(@Param("aircraftId") String aircraftId);

    @Select("SELECT sequence_number AS \"sequenceNumber\", point_code AS \"pointCode\", "
            + "nav_point_id AS \"navPointId\", latitude_deg AS \"latitudeDeg\", "
            + "longitude_deg AS \"longitudeDeg\", altitude_constraint_ft AS \"altitudeConstraintFt\", "
            + "speed_constraint_kt AS \"speedConstraintKt\", target_time_seconds AS \"targetTimeSeconds\" "
            + "FROM flight_plan_leg WHERE flight_plan_id = #{flightPlanId} "
            + "ORDER BY sequence_number")
    List<Map<String, Object>> listLegs(@Param("flightPlanId") String flightPlanId);

    /** 旧版本只读：任何修改尝试都必须返回 0 行。 */
    @Update("UPDATE flight_plan SET route_text = #{routeText} "
            + "WHERE aircraft_id = #{aircraftId} AND plan_version = #{planVersion}")
    int updateRouteText(@Param("aircraftId") String aircraftId,
                        @Param("planVersion") int planVersion,
                        @Param("routeText") String routeText);
}
