package org.bluesky.training.aircraft;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.reference.ReferenceSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** P07：机型模板补齐初始状态（详细设计 5.2.1/9.3：补齐后的最终值必须随响应返回）。 */
@Service
public class InitialStateTemplateService {

    private static final Map<String, Double> DEFAULT_ALTITUDE = new HashMap<>();
    private static final Map<String, Double> DEFAULT_IAS = new HashMap<>();
    private final ReferenceSnapshotService referenceSnapshotService;

    /** 保留迁移期无参构造，便于脱离 Spring 的纯规则测试。 */
    public InitialStateTemplateService() {
        this(null);
    }

    @Autowired
    public InitialStateTemplateService(ReferenceSnapshotService referenceSnapshotService) {
        this.referenceSnapshotService = referenceSnapshotService;
    }

    static {
        DEFAULT_ALTITUDE.put("A320", 8000.0);
        DEFAULT_ALTITUDE.put("B738", 8000.0);
        DEFAULT_ALTITUDE.put("C909", 6000.0);
        DEFAULT_IAS.put("A320", 250.0);
        DEFAULT_IAS.put("B738", 250.0);
        DEFAULT_IAS.put("C909", 220.0);
    }

    public Map<String, Object> completeInitialState(Map<String, Object> initialState,
                                                    String aircraftType, String originAirport) {
        return completeInitialState(initialState, aircraftType, originAirport, null);
    }

    /** 优先使用组固定快照中的模板；未固定快照或未提供模板时兼容默认模板。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> completeInitialState(Map<String, Object> initialState,
                                                    String aircraftType, String originAirport,
                                                    String groupId) {
        Map<String, Object> completed = initialState == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(initialState);

        if (groupId != null && referenceSnapshotService != null) {
            Object resource = referenceSnapshotService.readOptionalResource(
                    groupId, "initial-state-templates");
            Map<String, Object> template = findTemplate(resource, aircraftType, originAirport);
            if (template != null) {
                Map<String, Object> values = template.get("initialState") instanceof Map
                        ? (Map<String, Object>) template.get("initialState") : template;
                copyIfMissing(completed, values, "latitudeDeg", "latitude", "lat");
                copyIfMissing(completed, values, "longitudeDeg", "longitude", "lon");
                copyIfMissing(completed, values, "trueHeadingDeg", "trueHeading", "headingDeg");
                copyIfMissing(completed, values, "altitudeFtMsl", "altitudeFt", "altitude");
                copyIfMissing(completed, values, "indicatedAirspeedKt", "iasKt", "speedKt");
            }
        }

        // 模板和默认值都无法补齐的位置/航向：明确拒绝，避免生成不可复现初态
        require(completed, "latitudeDeg", aircraftType, originAirport);
        require(completed, "longitudeDeg", aircraftType, originAirport);
        require(completed, "trueHeadingDeg", aircraftType, originAirport);

        String type = aircraftType == null ? "" : aircraftType.trim().toUpperCase();
        if (completed.get("altitudeFtMsl") == null) {
            Double altitude = DEFAULT_ALTITUDE.get(type);
            if (altitude == null) {
                throw incomplete("altitudeFtMsl", aircraftType);
            }
            completed.put("altitudeFtMsl", altitude);
        }
        if (completed.get("indicatedAirspeedKt") == null) {
            Double ias = DEFAULT_IAS.get(type);
            if (ias == null) {
                throw incomplete("indicatedAirspeedKt", aircraftType);
            }
            completed.put("indicatedAirspeedKt", ias);
        }
        AircraftValidator.validateInitialState(completed);
        return completed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findTemplate(Object resource, String aircraftType,
                                                    String originAirport) {
        if (resource instanceof Map) {
            Object nested = ((Map<?, ?>) resource).get("templates");
            if (nested == null) {
                nested = ((Map<?, ?>) resource).get("items");
            }
            resource = nested;
        }
        if (!(resource instanceof java.util.List)) return null;
        String type = aircraftType == null ? "" : aircraftType.trim().toUpperCase();
        String origin = originAirport == null ? "" : originAirport.trim().toUpperCase();
        Map<String, Object> wildcard = null;
        for (Object value : (java.util.List<?>) resource) {
            if (!(value instanceof Map)) {
                continue;
            }
            Map<String, Object> candidate = (Map<String, Object>) value;
            String candidateType = text(candidate, "aircraftType", "aircraft_type", "type");
            String candidateOrigin = text(candidate, "origin", "originAirport", "airport");
            if (!type.equals(candidateType)) {
                continue;
            }
            if (origin.equals(candidateOrigin)) {
                return candidate;
            }
            if (candidateOrigin.isEmpty() || "*".equals(candidateOrigin)) {
                wildcard = candidate;
            }
        }
        return wildcard;
    }

    private static void copyIfMissing(Map<String, Object> target, Map<String, Object> source,
                                      String targetKey, String... aliases) {
        if (target.get(targetKey) != null) {
            return;
        }
        for (String alias : aliases) {
            if (source.get(alias) != null) {
                target.put(targetKey, source.get(alias));
                return;
            }
        }
    }

    private static String text(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            Object raw = value.get(key);
            if (raw != null && !String.valueOf(raw).trim().isEmpty()) {
                return String.valueOf(raw).trim().toUpperCase();
            }
        }
        return "";
    }

    private static void require(Map<String, Object> state, String field,
                                String aircraftType, String originAirport) {
        if (state.get(field) == null) {
            throw incomplete(field, aircraftType);
        }
    }

    private static V2DomainException incomplete(String field, String aircraftType) {
        return new V2DomainException("INITIAL_STATE_INCOMPLETE", 422,
                "初始状态缺失 " + field + " 且无法由模板补齐（机型 " + aircraftType + "）",
                Arrays.asList("initialState." + field));
    }
}
