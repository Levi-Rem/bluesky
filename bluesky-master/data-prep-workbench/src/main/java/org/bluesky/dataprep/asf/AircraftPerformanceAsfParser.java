package org.bluesky.dataprep.asf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for ACCOPS AIRCRAFT_PERFORMANCES.ASF. */
class AircraftPerformanceAsfParser {
    private static final Pattern GROUP = Pattern.compile("^/(\\d+)/$");

    Result parse(InputStream input) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        Result result = new Result();
        int index = 0;
        while (index < lines.size()) {
            Matcher marker = GROUP.matcher(lines.get(index).trim());
            if (!marker.matches()) { index++; continue; }
            int number = Integer.parseInt(marker.group(1));
            int next = index + 1;
            while (next < lines.size() && !GROUP.matcher(lines.get(next).trim()).matches()) next++;
            result.groups.add(parseGroup(number, lines.subList(index + 1, next), result.warnings));
            index = next;
        }
        if (result.groups.isEmpty()) throw new IllegalArgumentException("未找到机型性能分组");
        return result;
    }

    private GroupData parseGroup(int number, List<String> source, List<String> warnings) {
        GroupData group = new GroupData();
        group.number = number;
        int star = -1;
        for (int i = 0; i < source.size(); i++) {
            if ("*".equals(source.get(i).trim())) { star = i; break; }
            String line = source.get(i).trim();
            if (line.isEmpty() || line.startsWith("--")) continue;
            for (String token : line.split("\\s+")) {
                String[] parts = token.trim().split("/");
                if (parts.length == 3 && !parts[0].isEmpty()) {
                    group.aircraft.add(new Aircraft(parts[0], parts[1], parts[2]));
                }
            }
        }
        if (star < 0 || group.aircraft.isEmpty()) {
            throw new IllegalArgumentException("分组 " + number + " 缺少机型列表或 * 分隔符");
        }

        List<String> data = new ArrayList<>();
        for (int i = star + 1; i < source.size(); i++) {
            String line = source.get(i).trim();
            if (!line.isEmpty() && !line.startsWith("--")) data.add(line);
        }
        if (data.size() < 22) {
            throw new IllegalArgumentException("分组 " + number + " 性能数据不完整：仅 " + data.size() + " 行");
        }
        int p = 0;
        group.holding = cells(data.get(p++), 3);
        List<String> takeoff = cells(data.get(p++), 4);
        group.takeoffSpeed = takeoff.get(0);
        group.takeoffDurationS = integer(takeoff.get(1));
        group.takeoffAltitudeFt = integer(takeoff.get(2));
        group.takeoffDistanceNm = decimal(takeoff.get(3));
        group.landingSpeed = cells(data.get(p++), 1).get(0);
        group.radarCrossSection = decimal(cells(data.get(p++), 1).get(0));
        List<String> maximum = cells(data.get(p++), 5);
        group.maximumSpeed = maximum.get(0);
        group.maximumAltitudeLayer = maximum.get(1);
        group.maximumTurn = integer(maximum.get(2));
        group.machCapable = yes(maximum.get(3));
        group.jetAircraft = yes(maximum.get(4));
        group.standardTurn = integer(cells(data.get(p++), 1).get(0));
        for (int i = 0; i < 5; i++) group.responses.add(integers(cells(data.get(p++), 3)));
        int declaredLayers = integer(cells(data.get(p++), 1).get(0));
        group.altitudeLayers = matchingCells(data.get(p++), "(?i)F\\d+");
        for (int i = 0; i < 4; i++) group.curves.add(matchingCells(data.get(p++), "[+-]?\\d+"));
        for (int i = 0; i < 4; i++) group.curves.add(matchingCells(data.get(p++), "(?i)[NM]\\d+"));
        group.performanceCategory = data.get(p).trim().split("\\s+")[0];

        int actualLayers = group.altitudeLayers.size();
        if (declaredLayers != actualLayers) {
            warnings.add("分组 " + number + " 声明 " + declaredLayers + " 层，按实际 " + actualLayers + " 层导入");
        }
        for (int i = 0; i < group.curves.size(); i++) {
            int values = group.curves.get(i).size();
            if (values != actualLayers) {
                warnings.add("分组 " + number + " 第 " + (i + 1) + " 条性能曲线有 " + values
                        + " 个值，按 " + actualLayers + " 个高度层对齐");
            }
        }
        return group;
    }

    private static List<String> cells(String line, int limit) {
        List<String> values = new ArrayList<>();
        for (String part : line.split("\\|")) {
            String value = part.trim();
            if (!value.isEmpty()) values.add(value);
            if (values.size() == limit) break;
        }
        if (limit != Integer.MAX_VALUE && values.size() < limit) {
            throw new IllegalArgumentException("性能参数列不足：" + line);
        }
        return values;
    }

    private static List<String> matchingCells(String line, String pattern) {
        List<String> result = new ArrayList<>();
        for (String value : cells(line, Integer.MAX_VALUE)) {
            String token = value.trim();
            if (token.matches(pattern)) result.add(token);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("性能参数格式错误：" + line);
        return result;
    }

    private static int integer(String value) {
        String number = value.replaceAll("(?i)FT|NM", "").replaceAll("[^0-9-]", "");
        return number.isEmpty() ? 0 : Integer.parseInt(number);
    }

    private static double decimal(String value) {
        String number = value.replaceAll("(?i)FT|NM", "").replaceAll("[^0-9.-]", "");
        return number.isEmpty() ? 0 : Double.parseDouble(number);
    }

    private static List<Integer> integers(List<String> values) {
        return Arrays.asList(integer(values.get(0)), integer(values.get(1)), integer(values.get(2)));
    }

    private static boolean yes(String value) { return "Y".equalsIgnoreCase(value.trim()); }

    static class Result {
        final List<GroupData> groups = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    static class Aircraft {
        final String code;
        final String icaoWakeCategory;
        final String reacatWakeCategory;
        Aircraft(String code, String icao, String reacat) {
            this.code = code;
            this.icaoWakeCategory = icao;
            this.reacatWakeCategory = reacat;
        }
    }

    static class GroupData {
        int number;
        final List<Aircraft> aircraft = new ArrayList<>();
        List<String> holding;
        String takeoffSpeed;
        int takeoffDurationS;
        int takeoffAltitudeFt;
        double takeoffDistanceNm;
        String landingSpeed;
        double radarCrossSection;
        String maximumSpeed;
        String maximumAltitudeLayer;
        int maximumTurn;
        boolean machCapable;
        boolean jetAircraft;
        int standardTurn;
        final List<List<Integer>> responses = new ArrayList<>();
        List<String> altitudeLayers;
        final List<List<String>> curves = new ArrayList<>();
        String performanceCategory;
    }
}
