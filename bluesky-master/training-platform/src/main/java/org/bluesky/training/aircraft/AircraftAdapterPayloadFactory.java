package org.bluesky.training.aircraft;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将持久化航空器与最新计划组装成 AIRCRAFT_APPLY 的完整、可重放载荷。 */
@Component
public class AircraftAdapterPayloadFactory {

    private final AircraftV2Mapper aircraftMapper;
    private final FlightPlanService flightPlanService;

    public AircraftAdapterPayloadFactory(AircraftV2Mapper aircraftMapper,
                                         FlightPlanService flightPlanService) {
        this.aircraftMapper = aircraftMapper;
        this.flightPlanService = flightPlanService;
    }

    public Map<String, Object> build(String aircraftId) {
        Map<String, Object> aircraft = aircraftMapper.findById(aircraftId);
        if (aircraft == null) {
            throw new V2DomainException("TERMINAL_NOT_FOUND", 404,
                    "航空器不存在: " + aircraftId);
        }
        List<Map<String, Object>> versions = flightPlanService.listVersions(aircraftId);
        if (versions.isEmpty()) {
            throw new V2DomainException("TRAINING_STATE_INVALID", 409,
                    "航空器没有可下发的飞行计划: " + aircraftId);
        }
        Map<String, Object> latest = versions.get(0);
        List<String> route = new ArrayList<>();
        Object legs = latest.get("legs");
        if (legs instanceof List) {
            for (Object raw : (List<?>) legs) {
                if (raw instanceof Map && ((Map<?, ?>) raw).get("pointCode") != null) {
                    route.add(String.valueOf(((Map<?, ?>) raw).get("pointCode")));
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aircraftId", aircraftId);
        payload.put("callsign", aircraft.get("callsign"));
        payload.put("aircraftType", aircraft.get("aircraft_type"));
        payload.put("origin", latest.get("origin"));
        payload.put("destination", latest.get("destination"));
        payload.put("latitude", aircraft.get("latitude"));
        payload.put("longitude", aircraft.get("longitude"));
        payload.put("headingDegrees", aircraft.get("heading_degrees"));
        payload.put("altitudeFeet", aircraft.get("altitude_feet"));
        payload.put("speedKnots", aircraft.get("speed_knots"));
        payload.put("route", route);
        payload.put("icao24", aircraft.get("icao24"));
        payload.put("squawk", aircraft.get("planned_squawk"));
        payload.put("ssrMode", aircraft.get("ssr_mode"));
        return payload;
    }
}
