package org.bluesky.training.aircraft;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.FlightPlanMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P07：版本化飞行计划——只增版本，旧版本只读（详细设计 5.2.8）。 */
@Service
public class FlightPlanService {

    private final FlightPlanMapper planMapper;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public FlightPlanService(FlightPlanMapper planMapper) {
        this.planMapper = planMapper;
    }

    @Transactional
    public Map<String, Object> createVersion(String aircraftId, String origin, String destination,
                                             String plannedSquawk, String ssrMode,
                                             Integer cruiseAltitudeFtMsl,
                                             Integer cruiseIndicatedAirspeedKt,
                                             List<String> routePoints) {
        List<String> points = validateRoute(destination, routePoints);
        int nextVersion = planMapper.maxVersion(aircraftId) + 1;
        String planId = UUID.randomUUID().toString();
        planMapper.insertPlan(planId, aircraftId, nextVersion, origin, destination,
                plannedSquawk, ssrMode, cruiseAltitudeFtMsl, cruiseIndicatedAirspeedKt,
                String.join(" ", points));
        for (int i = 0; i < points.size(); i++) {
            planMapper.insertLeg(UUID.randomUUID().toString(), planId, i + 1,
                    null, points.get(i), null, null, false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", planId);
        body.put("aircraftId", aircraftId);
        body.put("planVersion", nextVersion);
        body.put("legs", points.size());
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listVersions(String aircraftId) {
        List<Map<String, Object>> versions = planMapper.listVersions(aircraftId);
        for (Map<String, Object> version : versions) {
            version.put("legs", planMapper.listLegs(String.valueOf(version.get("id"))));
        }
        return versions;
    }

    /** Apply acknowledged route/leg changes in the same transaction as completion. */
    @Transactional
    public void applyInstructionVersion(String aircraftId, String type, Map<String,Object> parameters) {
        if (!Arrays.asList("RTE","SIDSTAR","P_LEVEL","P_TIME").contains(type)) return;
        List<Map<String,Object>> versions=planMapper.listVersions(aircraftId);
        if (versions.isEmpty()) throw new V2DomainException("REFERENCE_NOT_FOUND",422,"航空器没有当前飞行计划");
        Map<String,Object> previous=versions.get(0);
        List<String> points=new ArrayList<>();
        if (Arrays.asList("RTE","SIDSTAR").contains(type)) {
            for (Object point:(List<?>)parameters.get("route")) points.add(String.valueOf(point));
        } else for(Map<String,Object> leg:planMapper.listLegs(String.valueOf(previous.get("id")))) points.add(String.valueOf(leg.get("pointCode")));
        Map<String,Object> created=createVersion(aircraftId,(String)previous.get("origin"),(String)previous.get("destination"),
                (String)previous.get("plannedSquawk"),(String)previous.get("ssrMode"),
                integer(previous.get("cruiseAltitudeFtMsl")),integer(previous.get("cruiseIndicatedAirspeedKt")),points);
        if ("P_LEVEL".equals(type) || "P_TIME".equals(type)) {
            String id=String.valueOf(created.get("id"));
            jdbc.update("DELETE FROM flight_plan_leg WHERE flight_plan_id=?",id);
            for(Map<String,Object> leg:jdbc.queryForList("SELECT * FROM flight_plan_leg WHERE flight_plan_id=? ORDER BY sequence_number",previous.get("id"))) {
                jdbc.update("INSERT INTO flight_plan_leg(id,flight_plan_id,sequence_number,nav_point_id,point_code,latitude_deg,longitude_deg,fly_over,altitude_constraint_ft,speed_constraint_kt,target_time_seconds,procedure_kind,runway_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(),id,leg.get("sequence_number"),leg.get("nav_point_id"),leg.get("point_code"),leg.get("latitude_deg"),leg.get("longitude_deg"),leg.get("fly_over"),leg.get("altitude_constraint_ft"),leg.get("speed_constraint_kt"),leg.get("target_time_seconds"),leg.get("procedure_kind"),leg.get("runway_id"));
            }
            String column="P_LEVEL".equals(type)?"altitude_constraint_ft":"target_time_seconds";
            Object value=parameters.get("P_LEVEL".equals(type)?"altitudeFtMsl":"targetTimeSeconds");
            if(jdbc.update("UPDATE flight_plan_leg SET "+column+"=? WHERE flight_plan_id=? AND point_code=?",value,id,parameters.get("legPoint"))!=1)
                throw new V2DomainException("REFERENCE_NOT_FOUND",422,"计划目标航段不唯一或已失效");
        }
    }

    private static Integer integer(Object value) { return value instanceof Number?((Number)value).intValue():null; }

    /** 旧版本只读：对非当前版本的一切更新必须拒绝。 */
    @Transactional
    public void assertVersionWritable(String aircraftId, int planVersion) {
        int maxVersion = planMapper.maxVersion(aircraftId);
        if (planVersion != maxVersion) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "只能修改当前版本（v" + maxVersion + "），旧版本只读",
                    Arrays.asList("planVersion"));
        }
    }

    private static List<String> validateRoute(String destination, List<String> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "航路不能为空", Arrays.asList("route"));
        }
        List<String> points = new ArrayList<>();
        for (String point : routePoints) {
            String code = point == null ? "" : point.trim().toUpperCase();
            if (code.isEmpty() || !code.matches("^[A-Z0-9]{2,8}$")) {
                throw new V2DomainException("INVALID_INSTRUCTION", 400,
                        "航路点非法: " + point, Arrays.asList("route"));
            }
            points.add(code);
        }
        if (destination != null && !points.get(points.size() - 1)
                .equals(destination.trim().toUpperCase())) {
            throw new V2DomainException("INVALID_INSTRUCTION", 400,
                    "航路最后一点必须是落地机场 " + destination, Arrays.asList("route"));
        }
        return points;
    }
}
