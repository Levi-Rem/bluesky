package org.bluesky.dataprep.asf;

import org.bluesky.dataprep.common.ApiException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 FDP_VOLUMES_DEFINITION.ASF 中的点、层、基础体积、扇区和 FIR。 */
public class FdpVolumesParser {
    private static final Pattern DMS = Pattern.compile(
            "^(\\d{2})(\\d{2})(\\d{2})([NS])(\\d{3})(\\d{2})(\\d{2})([EW])$");

    public Result parse(InputStream input) {
        Map<String, List<SourceLine>> sections = readSections(input);
        Result result = new Result();
        parsePoints(sections.get("POINTS"), result);
        parseLayers(sections.get("LAYER"), result);
        parseVolumes(sections.get("VOLUME"), result);
        parseSectors(sections.get("SECTOR"), result);
        parseFirs(sections.get("FIR"), result);
        validate(result);
        return result;
    }

    private static Map<String, List<SourceLine>> readSections(InputStream input) {
        Map<String, List<SourceLine>> sections = new LinkedHashMap<>();
        String section = null;
        int lineNo = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String clean = stripBom(line).trim();
                if (clean.matches("^/[^/]+/$")) {
                    section = clean.substring(1, clean.length() - 1);
                    sections.putIfAbsent(section, new ArrayList<SourceLine>());
                    continue;
                }
                if (section != null) sections.get(section).add(new SourceLine(lineNo, line));
            }
        } catch (IOException ex) {
            throw ApiException.badRequest("FDP 体积 ASF 读取失败：" + ex.getMessage());
        }
        return sections;
    }

    private static void parsePoints(List<SourceLine> lines, Result result) {
        for (SourceLine line : required(lines, "POINTS")) {
            String[] fields = fields(line.text, 2);
            if (fields == null || fields[0].isEmpty()) continue;
            Point point = new Point();
            point.name = fields[0];
            point.coordinateText = fields[1];
            point.lineNumber = line.number;
            double[] coordinate = coordinate(fields[1], line.number);
            point.latitude = coordinate[0];
            point.longitude = coordinate[1];
            result.points.put(point.name, point);
        }
    }

    private static void parseLayers(List<SourceLine> lines, Result result) {
        for (SourceLine line : required(lines, "LAYER")) {
            String[] fields = fields(line.text, 2);
            if (fields == null || fields[0].isEmpty()) continue;
            try {
                result.layerUpperLimits.put(Integer.parseInt(fields[0]), fields[1]);
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("FDP 体积 ASF 第 " + line.number + " 行层编号不合法");
            }
        }
    }

    private static void parseVolumes(List<SourceLine> lines, Result result) {
        Volume current = null;
        for (SourceLine line : required(lines, "VOLUME")) {
            String[] fields = fields(line.text, 3);
            if (fields == null) continue;
            if (!fields[0].isEmpty()) {
                if (current != null) finishVolume(current, result);
                current = new Volume();
                current.name = fields[0];
                current.lineNumber = line.number;
                int[] range = layerRange(fields[1], line.number);
                current.layerStart = range[0];
                current.layerEnd = range[1];
                current.composition.append(fields[2]);
            } else if (current != null) {
                current.composition.append(' ').append(fields[2]);
            }
        }
        if (current != null) finishVolume(current, result);
    }

    private static void finishVolume(Volume volume, Result result) {
        volume.pointNames.addAll(tokens(volume.composition.toString()));
        if (volume.pointNames.size() > 1
                && volume.pointNames.get(0).equals(volume.pointNames.get(volume.pointNames.size() - 1))) {
            volume.pointNames.remove(volume.pointNames.size() - 1);
        }
        result.volumes.put(volume.name, volume);
    }

    private static void parseSectors(List<SourceLine> lines, Result result) {
        Sector current = null;
        for (SourceLine line : required(lines, "SECTOR")) {
            String[] fields = fields(line.text, 4);
            if (fields == null) continue;
            if (!fields[0].isEmpty()) {
                if (current != null) finishSector(current, result.sectors);
                current = new Sector();
                current.name = fields[0];
                current.sourceSubtype = fields[1];
                current.sourceFlag = fields[2];
                current.lineNumber = line.number;
                current.composition.append(fields[3]);
            } else if (current != null) {
                current.composition.append(' ').append(fields[3]);
            }
        }
        if (current != null) finishSector(current, result.sectors);
    }

    private static void parseFirs(List<SourceLine> lines, Result result) {
        Sector current = null;
        for (SourceLine line : required(lines, "FIR")) {
            String[] fields = fields(line.text, 2);
            if (fields == null) continue;
            if (!fields[0].isEmpty()) {
                if (current != null) finishSector(current, result.firs);
                current = new Sector();
                current.name = fields[0];
                current.lineNumber = line.number;
                current.composition.append(fields[1]);
            } else if (current != null) {
                current.composition.append(' ').append(fields[1]);
            }
        }
        if (current != null) finishSector(current, result.firs);
    }

    private static void finishSector(Sector sector, List<Sector> target) {
        sector.volumeNames.addAll(tokens(sector.composition.toString().replace('+', ' ')));
        target.add(sector);
    }

    private static void validate(Result result) {
        if (result.points.isEmpty() || result.layerUpperLimits.isEmpty() || result.volumes.isEmpty()
                || (result.sectors.isEmpty() && result.firs.isEmpty())) {
            throw ApiException.badRequest("FDP 体积 ASF 缺少 POINTS、LAYER、VOLUME、SECTOR/FIR 数据段");
        }
        List<String> missing = new ArrayList<>();
        for (Volume volume : result.volumes.values()) {
            for (String point : volume.pointNames) {
                if (!result.points.containsKey(point)) addMissing(missing, "体积 " + volume.name + " → " + point);
            }
            for (int layer = volume.layerStart; layer <= volume.layerEnd; layer++) {
                if (!result.layerUpperLimits.containsKey(layer)) addMissing(missing, "体积 " + volume.name + " → 层 " + layer);
            }
        }
        for (Sector sector : combined(result.sectors, result.firs)) {
            for (String volume : sector.volumeNames) {
                if (!result.volumes.containsKey(volume)) addMissing(missing, sector.name + " → 体积 " + volume);
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("FDP 体积 ASF 存在缺失引用：" + String.join("，", missing));
        }
    }

    private static List<Sector> combined(List<Sector> left, List<Sector> right) {
        List<Sector> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private static void addMissing(List<String> missing, String value) {
        if (missing.size() < 20) missing.add(value);
    }

    private static List<SourceLine> required(List<SourceLine> lines, String section) {
        if (lines == null) throw ApiException.badRequest("FDP 体积 ASF 缺少 /" + section + "/ 数据段");
        return lines;
    }

    private static String[] fields(String source, int minimum) {
        String clean = source;
        int comment = clean.indexOf("--");
        if (comment >= 0) clean = clean.substring(0, comment);
        if (clean.trim().isEmpty() || !clean.contains("|")) return null;
        String[] raw = clean.split("\\|", -1);
        if (raw.length < minimum) return null;
        String[] result = new String[minimum];
        for (int i = 0; i < minimum; i++) result[i] = raw[i].trim();
        return result;
    }

    private static int[] layerRange(String value, int line) {
        String[] parts = value.trim().split("-");
        try {
            int start = Integer.parseInt(parts[0].trim());
            int end = parts.length == 1 ? start : Integer.parseInt(parts[1].trim());
            if (start < 1 || end < start) throw new NumberFormatException();
            return new int[]{start, end};
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("FDP 体积 ASF 第 " + line + " 行层范围不合法：" + value);
        }
    }

    private static List<String> tokens(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
    }

    private static double[] coordinate(String value, int line) {
        Matcher matcher = DMS.matcher(value);
        if (!matcher.matches()) {
            throw ApiException.badRequest("FDP 体积 ASF 第 " + line + " 行坐标不合法：" + value);
        }
        int latMin = Integer.parseInt(matcher.group(2));
        int latSec = Integer.parseInt(matcher.group(3));
        int lonMin = Integer.parseInt(matcher.group(6));
        int lonSec = Integer.parseInt(matcher.group(7));
        double lat = Integer.parseInt(matcher.group(1)) + latMin / 60d + latSec / 3600d;
        double lon = Integer.parseInt(matcher.group(5)) + lonMin / 60d + lonSec / 3600d;
        if ("S".equals(matcher.group(4))) lat = -lat;
        if ("W".equals(matcher.group(8))) lon = -lon;
        return new double[]{lat, lon};
    }

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    static class SourceLine {
        final int number;
        final String text;
        SourceLine(int number, String text) { this.number = number; this.text = text; }
    }

    public static class Point {
        String name;
        String coordinateText;
        double longitude;
        double latitude;
        int lineNumber;
    }

    public static class Volume {
        String name;
        int layerStart;
        int layerEnd;
        int lineNumber;
        final StringBuilder composition = new StringBuilder();
        final List<String> pointNames = new ArrayList<>();
    }

    public static class Sector {
        String name;
        String sourceSubtype;
        String sourceFlag;
        int lineNumber;
        final StringBuilder composition = new StringBuilder();
        final List<String> volumeNames = new ArrayList<>();
    }

    public static class Result {
        final Map<String, Point> points = new LinkedHashMap<>();
        final Map<Integer, String> layerUpperLimits = new LinkedHashMap<>();
        final Map<String, Volume> volumes = new LinkedHashMap<>();
        final List<Sector> sectors = new ArrayList<>();
        final List<Sector> firs = new ArrayList<>();
    }
}
