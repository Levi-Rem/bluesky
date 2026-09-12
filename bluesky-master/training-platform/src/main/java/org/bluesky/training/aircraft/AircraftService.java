package org.bluesky.training.aircraft;

import org.bluesky.training.adapter.SimulationGateway;
import org.bluesky.training.configuration.FieldValidationException;
import org.bluesky.training.persistence.AircraftMapper;
import org.bluesky.training.persistence.AircraftRow;
import org.bluesky.training.persistence.AircraftV2Mapper;
import org.bluesky.training.mapdata.ReferenceDataResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AircraftService {
    private final AircraftMapper aircraftMapper;
    private final SimulationGateway simulationGateway;
    private final ReferenceDataResolver referenceDataResolver;
    private final AircraftV2Mapper aircraftV2Mapper;
    private final FlightPlanService flightPlanService;

    public AircraftService(AircraftMapper aircraftMapper, SimulationGateway simulationGateway,
                           ReferenceDataResolver referenceDataResolver,
                           AircraftV2Mapper aircraftV2Mapper,
                           FlightPlanService flightPlanService) {
        this.aircraftMapper = aircraftMapper;
        this.simulationGateway = simulationGateway;
        this.referenceDataResolver = referenceDataResolver;
        this.aircraftV2Mapper = aircraftV2Mapper;
        this.flightPlanService = flightPlanService;
    }

    @Transactional
    public AircraftResponse create(String groupId, CreateAircraftRequest request) {
        requireDefaultGroup(groupId);
        AircraftCreateCommand command = referenceDataResolver.resolve(normalize(request));
        if (aircraftMapper.findByCallsign(command.getCallsign()) != null) {
            throw new FieldValidationException("callsign", "当前训练组已存在该呼号");
        }
        simulationGateway.createAircraft(command);

        AircraftRow row = new AircraftRow();
        row.setId(UUID.randomUUID().toString());
        row.setAssignedTerminalId("PP-DEFAULT");
        row.setCallsign(command.getCallsign());
        row.setAircraftType(command.getAircraftType());
        row.setWakeCategory(command.getWakeCategory());
        row.setTransponderCode(command.getTransponderCode());
        row.setOrigin(command.getOrigin());
        row.setDestination(command.getDestination());
        row.setAppearanceOffsetMinutes(command.getAppearanceOffsetMinutes());
        row.setLatitude(command.getLatitude());
        row.setLongitude(command.getLongitude());
        row.setInitialWaypoint(command.getInitialWaypoint());
        row.setHeadingDegrees(command.getHeadingDegrees());
        row.setAltitudeFeet(command.getAltitudeFeet());
        row.setSpeedKnots(command.getSpeedKnots());
        row.setRouteText(String.join(" ", command.getRoute()));
        // v1 同步建机成功即视为已出现（v2 语义详见设计 5.2）
        row.setLifecycle("ACTIVE");
        aircraftMapper.insert(row);
        // v1/v2 共存：v1 建机同样写 v2 当前责任分配（详细设计 5.0 每机恰一条当前分配）
        aircraftV2Mapper.insertAssignment(UUID.randomUUID().toString(), row.getId(),
                "PP-DEFAULT");
        // v1/v2 共存：v1 建机同样落版本化飞行计划（P_LEVEL/P_TIME 航段冲突键依赖 leg 行）
        flightPlanService.createVersion(row.getId(), row.getOrigin(), row.getDestination(),
                row.getTransponderCode(), null, null, null, command.getRoute());
        return new AircraftResponse(row);
    }

    public List<AircraftResponse> list(String groupId) {
        requireDefaultGroup(groupId);
        return aircraftMapper.findAllDefaultGroup().stream().map(AircraftResponse::new).collect(Collectors.toList());
    }

    @Transactional
    public boolean delete(String aircraftId) {
        AircraftRow row = aircraftMapper.findById(aircraftId);
        if (row == null) {
            return false;
        }
        simulationGateway.deleteAircraft(row.getCallsign());
        aircraftMapper.deleteInstructions(aircraftId);
        aircraftMapper.deleteById(aircraftId);
        return true;
    }

    private AircraftCreateCommand normalize(CreateAircraftRequest request) {
        validateTransponderCode(request.getTransponderCode());
        int appearanceOffsetMinutes = validateAppearanceOffset(request.getAppearanceOffsetMinutes());
        requireText(request.getCallsign(), "callsign", "呼号不能为空");
        requireText(request.getAircraftType(), "aircraftType", "机型不能为空");
        requireText(request.getWakeCategory(), "wakeCategory", "尾流类别不能为空");
        requireText(request.getOrigin(), "origin", "起飞机场不能为空");
        requireText(request.getDestination(), "destination", "落地机场不能为空");
        if (!upper(request.getCallsign()).matches("[A-Z0-9]{2,7}")) {
            throw new FieldValidationException("callsign", "呼号必须是 2 至 7 位英文字母或数字");
        }
        if (upper(request.getAircraftType()).length() > 4) {
            throw new FieldValidationException("aircraftType", "机型最多 4 个字符");
        }
        if (upper(request.getWakeCategory()).length() != 1) {
            throw new FieldValidationException("wakeCategory", "尾流类别必须是 1 个字符");
        }
        if (request.getHeadingDegrees() < 0 || request.getHeadingDegrees() > 360) {
            throw new FieldValidationException("headingDegrees", "航向必须在 0 至 360 度之间");
        }
        if (request.getAltitudeFeet() < 0) {
            throw new FieldValidationException("altitudeFeet", "高度不能小于 0 ft");
        }
        if (request.getSpeedKnots() <= 0) {
            throw new FieldValidationException("speedKnots", "速度必须大于 0 kt");
        }
        if ((request.getLatitude() == null || request.getLongitude() == null)
                && (request.getInitialWaypoint() == null || request.getInitialWaypoint().trim().isEmpty())) {
            throw new FieldValidationException("position", "必须填写经纬度或初始航路点");
        }
        List<String> route = request.getRoute() == null ? Collections.emptyList()
                : request.getRoute().stream().map(this::upper).collect(Collectors.toList());
        if (route.isEmpty()) route = Collections.singletonList(upper(request.getDestination()));
        return new AircraftCreateCommand(
                upper(request.getCallsign()), upper(request.getAircraftType()), upper(request.getWakeCategory()),
                normalizedTransponderCode(request.getTransponderCode()),
                upper(request.getOrigin()), upper(request.getDestination()),
                appearanceOffsetMinutes, request.getLatitude(), request.getLongitude(),
                upperNullable(request.getInitialWaypoint()), request.getHeadingDegrees(), request.getAltitudeFeet(),
                request.getSpeedKnots(), route);
    }

    private void validateTransponderCode(String code) {
        if (code != null && !code.trim().isEmpty()
                && (!code.matches("[0-7]{4}") || "0000".equals(code))) {
            throw new FieldValidationException(
                    "transponderCode", "二次代码必须是四位八进制数且不能为 0000");
        }
    }

    private String normalizedTransponderCode(String code) {
        return code == null || code.trim().isEmpty() ? null : code.trim();
    }

    private int validateAppearanceOffset(String value) {
        if (value == null || !value.matches("[0-9]{4}")) {
            throw new FieldValidationException(
                    "appearanceOffsetMinutes", "出现时间必须是恰好四位数字，例如 0010");
        }
        return Integer.parseInt(value);
    }

    private void requireText(String value, String field, String message) {
        if (value == null || value.trim().isEmpty()) throw new FieldValidationException(field, message);
    }

    private String upper(String value) { return value == null ? null : value.trim().toUpperCase(Locale.ROOT); }
    private String upperNullable(String value) { return value == null || value.trim().isEmpty() ? null : upper(value); }
    private void requireDefaultGroup(String groupId) {
        if (!"GROUP-DEFAULT".equals(groupId)) throw new IllegalArgumentException("首版只支持默认训练组");
    }
}
