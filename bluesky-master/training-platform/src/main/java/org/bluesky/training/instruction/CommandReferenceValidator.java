package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.reference.ReferenceSnapshotService;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Resolve navigation commands only against the group's immutable snapshot. */
@Component
public class CommandReferenceValidator {
    private final ReferenceSnapshotService snapshots;
    private final AircraftV2Mapper aircraftMapper;
    public CommandReferenceValidator(ReferenceSnapshotService snapshots,AircraftV2Mapper aircraftMapper) {
        this.snapshots=snapshots; this.aircraftMapper=aircraftMapper;
    }
    public void validate(String type,Map<String,Object> p,Map<String,Object> aircraft,double now) {
        String group=String.valueOf(aircraft.get("exercise_group_id"));
        switch(type) {
            case "DCT": point(group,p.get("targetPoint")); break;
            case "RTE":
                List<?> route = (List<?>)p.get("route");
                for(Object code:route)point(group,code);
                if (!String.valueOf(aircraft.get("destination")).equalsIgnoreCase(String.valueOf(route.get(route.size()-1))))
                    throw new V2DomainException("INVALID_INSTRUCTION",400,"航路最后一点必须是落地机场");
                break;
            case "RESUME": if(p.get("resumePoint")!=null)point(group,p.get("resumePoint")); break;
            case "ORBIT": if(p.get("centerPoint")!=null)point(group,p.get("centerPoint")); break;
            case "HOLD":
                if("PUBLISHED_PROCEDURE".equals(p.get("action"))) {
                    Map<String,Object> hold=find(group,"holding-patterns",p.get("procedureName"),null);
                    Map<String,Object> geometry=new LinkedHashMap<>(hold);
                    geometry.remove("procedureName"); geometry.remove("action");
                    Map<String,Object> validated=StructuredCommandParser.parse("HOLD",geometry,null,now);
                    p.clear();p.putAll(validated);
                }
                if(p.get("fixPoint")!=null)point(group,p.get("fixPoint")); break;
            case "VOR":
                Map<String,Object> vor=find(group,"navaids",p.get("station"),null);
                if(!Arrays.asList("VOR","VOR_DME").contains(String.valueOf(vor.get("type"))))throw missing("VOR 台站",p.get("station")); break;
            case "SIDSTAR":
                Map<String,Object> procedure=find(group,"procedures",p.get("procedureId"),null);
                String airport=String.valueOf(procedure.get("airportCode"));
                if(!airport.equals(String.valueOf(aircraft.get("origin"))) && !airport.equals(String.valueOf(aircraft.get("destination")))) throw missing("本机机场程序",p.get("procedureId"));
                List<Object> procedureRoute = routeOf(procedure);
                Object destination = aircraft.get("destination");
                if (!String.valueOf(destination).equalsIgnoreCase(String.valueOf(procedureRoute.get(procedureRoute.size()-1))))
                    procedureRoute.add(destination);
                p.put("route",procedureRoute);
                for(Object code:procedureRoute)point(group,code);
                break;
            case "P_LEVEL": case "P_TIME":
                if(aircraftMapper.findCurrentLegIdByPoint(String.valueOf(aircraft.get("id")),String.valueOf(p.get("legPoint")))==null)throw missing("未来航段",p.get("legPoint"));
                if("P_TIME".equals(type) && ((Number)p.get("targetTimeSeconds")).doubleValue()<=now)throw new V2DomainException("INVALID_INSTRUCTION",400,"目标时刻必须晚于当前仿真时刻"); break;
            case "TAKEOFF": case "ILS":
                if("TAKEOFF".equals(type))LandingCommandParsers.validateTakeoffPhase(String.valueOf(aircraft.get("flight_phase")));
                String apt=String.valueOf("TAKEOFF".equals(type)?aircraft.get("origin"):p.get("airportCode"));
                Map<String,Object> runway=find(group,"runways",p.get("runway"),apt);
                if("ILS".equals(type) && !Boolean.TRUE.equals(runway.get("ilsAvailable")))throw missing("ILS 跑道",apt+p.get("runway"));
                // Explicit runway geometry is required; airport-centre approximation cannot prove touchdown.
                for(String field:Arrays.asList("thresholdLatitude","thresholdLongitude","elevationFt","trueHeadingDeg")) {
                    if(!(runway.get(field) instanceof Number))throw missing("跑道字段",field);
                    p.put(field,runway.get(field));
                }
                if ("ILS".equals(type)) {
                    Object threshold = runway.get("landingStopSpeedKt");
                    if (threshold != null && (!(threshold instanceof Number) || !Double.isFinite(((Number)threshold).doubleValue())
                            || ((Number)threshold).doubleValue()<0 || ((Number)threshold).doubleValue()>100))
                        throw missing("着陆停止速度阈值",threshold);
                    p.put("landingStopSpeedKt",threshold==null?0:threshold);
                }
                p.put("airportCode",apt); break;
            case "MISSED":
                ProcedureCommandParsers.validateMissedPhase(String.valueOf(aircraft.get("flight_phase")));
                if(p.get("missedProcedureId")==null)throw missing("复飞程序",null);
                Map<String,Object> missed=find(group,"procedures",p.get("missedProcedureId"),null);
                p.put("route",routeOf(missed));for(Object code:(List<?>)p.get("route"))point(group,code); break;
            default: break;
        }
    }
    private void point(String group,Object code) {
        for(String resource:Arrays.asList("navaids","airports")) for(Map<String,Object> row:rows(group,resource))if(matches(row,code))return;
        throw missing("航路点",code);
    }
    private Map<String,Object> find(String group,String resource,Object code,String airport) {
        List<Map<String,Object>> found=new ArrayList<>();
        for(Map<String,Object> row:rows(group,resource))if(matches(row,code) && (airport==null || airport.equals(String.valueOf(row.get("airportCode")))))found.add(row);
        if(found.size()!=1)throw missing(resource,code);return found.get(0);
    }
    private boolean matches(Map<String,Object> row,Object code) {
        if(code==null)return false;
        for(String key:Arrays.asList("id","code","name","runway","runwayCode","procedureId","procedureName"))if(String.valueOf(code).equalsIgnoreCase(String.valueOf(row.get(key))))return true;
        return false;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rows(String group,String resource) {
        Object value=snapshots.readOptionalResource(group,resource);
        if(value instanceof Map)value=((Map<?,?>)value).get("items");
        return value instanceof List?(List<Map<String,Object>>)value:Collections.emptyList();
    }
    private List<Object> routeOf(Map<String,Object> row) {
        Object route=row.get("route");if(!(route instanceof List))route=row.get("legs");
        if(!(route instanceof List) || ((List<?>)route).isEmpty())throw missing("程序航路",row.get("id"));
        List<Object> points=new ArrayList<>();for(Object leg:(List<?>)route)points.add(leg instanceof Map?((Map<?,?>)leg).get("point"):leg);return points;
    }
    private V2DomainException missing(String type,Object code){return new V2DomainException("REFERENCE_NOT_FOUND",422,"固定快照缺少唯一且可用的 "+type+": "+code);}
}
