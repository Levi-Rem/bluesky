package org.bluesky.dataprep.asf;

import org.bluesky.dataprep.common.ApiException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ACCOPS ASF 文本解析器：读取特征点、编码航路、SID 与 STAR。 */
public class AsfParser {

    private static final Pattern COORDINATE = Pattern.compile(
            "^(\\d{2})(\\d{2})(\\d{2})([NS])(\\d{3})(\\d{2})(\\d{2})([EW])$");

    public PointResult parsePoints(InputStream input) {
        PointResult result = new PointResult();
        boolean inDefinitions = false;
        int lineNumber = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = stripBom(line).trim();
                if ("/DEFINITIONS/".equals(trimmed)) {
                    inDefinitions = true;
                    continue;
                }
                if (inDefinitions && trimmed.startsWith("/") && trimmed.endsWith("/")) {
                    break;
                }
                if (!inDefinitions || ignored(trimmed) || !line.contains("|")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                if (fields.length < 3) {
                    continue;
                }
                Point point = new Point();
                point.code = fields[0].trim();
                point.coordinateText = fields[1].trim();
                point.sourcePointType = fields[2].trim();
                point.relevantFlag = field(fields, 3);
                point.applicableAirports = field(fields, 4);
                point.pilotFlag = field(fields, 5);
                point.dtiFlag = field(fields, 6);
                point.tfmFlag = field(fields, 7);
                point.comment = field(fields, 8);
                point.lineNumber = lineNumber;
                if (point.code.isEmpty()) {
                    continue;
                }
                double[] coordinate = parseCoordinate(point.coordinateText, lineNumber);
                point.latitude = coordinate[0];
                point.longitude = coordinate[1];
                Point prior = result.points.get(point.code);
                if (prior == null) {
                    result.points.put(point.code, point);
                } else {
                    String kind = prior.sameDefinition(point) ? "重复定义" : "冲突定义";
                    result.conflicts.add(point.code + "：第 " + point.lineNumber + " 行" + kind
                            + "，已保留第 " + prior.lineNumber + " 行");
                }
            }
        } catch (IOException ex) {
            throw ApiException.badRequest("特征点 ASF 读取失败：" + ex.getMessage());
        }
        if (!inDefinitions || result.points.isEmpty()) {
            throw ApiException.badRequest("特征点 ASF 缺少 /DEFINITIONS/ 数据段");
        }
        return result;
    }

    public List<Route> parseRoutes(InputStream input) {
        Map<String, Route> routes = new LinkedHashMap<>();
        String section = null;
        boolean foundCodedRoute = false;
        boolean foundSid = false;
        boolean foundStar = false;
        Route current = null;
        int lineNumber = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = stripBom(line).trim();
                if ("/CODED_ROUTE/".equals(trimmed)) {
                    section = "CODED_ROUTE";
                    foundCodedRoute = true;
                    current = null;
                    continue;
                }
                if ("/CODED_ROUTE_SEGMENTS/".equals(trimmed)) {
                    section = null;
                    current = null;
                    continue;
                }
                if ("/SID/".equals(trimmed)) {
                    section = "SID";
                    foundSid = true;
                    current = null;
                    continue;
                }
                if ("/STAR/".equals(trimmed)) {
                    section = "STAR";
                    foundStar = true;
                    current = null;
                    continue;
                }
                if (section != null && trimmed.startsWith("/") && trimmed.endsWith("/")) {
                    section = null;
                    current = null;
                    continue;
                }
                if (section == null || ignored(trimmed) || !line.contains("|")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                String code = field(fields, 0);
                if ("FPL_PBN_MISMATCH_DISPLAY".equals(code)) {
                    current = null;
                    continue;
                }
                if ("ELIGIBLE_ROUTE".equals(code)) {
                    if (current != null) {
                        current.eligibleRoute = field(fields, 1);
                    }
                    continue;
                }
                if (!code.isEmpty()) {
                    if (routes.containsKey(code)) {
                        throw ApiException.badRequest("航路 ASF 第 " + lineNumber + " 行存在重复航路编码：" + code);
                    }
                    current = new Route();
                    current.code = code;
                    current.routeType = section;
                    current.lineNumber = lineNumber;
                    if ("CODED_ROUTE".equals(section)) {
                        current.sense = field(fields, 1);
                        // V5 第 3~6 列依次为 RNAV、RNAV(2012)、RNP(2012)、RVSM。
                        current.rnavCapability = field(fields, 2);
                        current.rnavCapabilityPost2012 = field(fields, 3);
                        current.rnpCapabilityPost2012 = field(fields, 4);
                        current.rvsmLevel = field(fields, 5);
                    } else {
                        current.sense = "N";
                        current.procedureAirport = field(fields, 1);
                        current.procedureProfile = field(fields, 2);
                        current.procedureRunway = field(fields, 3);
                        current.procedureDirection = field(fields, 4);
                        current.procedureOperation = field(fields, 5);
                    }
                    routes.put(code, current);
                }
                if (current != null) {
                    // 三类记录都将点序列放在第 7 列；编码航路允许续行。
                    addPointCodes(current.pointCodes, field(fields, 6));
                }
            }
        } catch (IOException ex) {
            throw ApiException.badRequest("航路 ASF 读取失败：" + ex.getMessage());
        }
        if (!foundCodedRoute || !foundSid || !foundStar || routes.isEmpty()) {
            throw ApiException.badRequest("航路 ASF 必须同时包含 /CODED_ROUTE/、/SID/、/STAR/ 数据段");
        }
        for (Route route : routes.values()) {
            if (route.pointCodes.size() < 2) {
                throw ApiException.badRequest("航路 " + route.code + " 的有效航路点少于 2 个");
            }
        }
        return new ArrayList<>(routes.values());
    }

    private static void addPointCodes(List<String> target, String text) {
        String withoutComment = text.split("--", 2)[0].trim();
        if (withoutComment.isEmpty()) {
            return;
        }
        for (String code : withoutComment.split("\\s+")) {
            if (!code.isEmpty()) {
                target.add(code);
            }
        }
    }

    static double[] parseCoordinate(String value, int lineNumber) {
        Matcher matcher = COORDINATE.matcher(value);
        if (!matcher.matches()) {
            throw ApiException.badRequest("特征点 ASF 第 " + lineNumber + " 行坐标格式错误：" + value);
        }
        double latitude = decimal(matcher.group(1), matcher.group(2), matcher.group(3));
        double longitude = decimal(matcher.group(5), matcher.group(6), matcher.group(7));
        if ("S".equals(matcher.group(4))) {
            latitude = -latitude;
        }
        if ("W".equals(matcher.group(8))) {
            longitude = -longitude;
        }
        return new double[]{round6(latitude), round6(longitude)};
    }

    private static double decimal(String degrees, String minutes, String seconds) {
        int minute = Integer.parseInt(minutes);
        int second = Integer.parseInt(seconds);
        if (minute > 59 || second > 59) {
            throw ApiException.badRequest("ASF 经纬度分秒超出范围");
        }
        return Integer.parseInt(degrees) + minute / 60.0 + second / 3600.0;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static boolean ignored(String line) {
        return line.isEmpty() || line.startsWith("--") || line.startsWith("--------");
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index].trim() : "";
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    public static class PointResult {
        private final Map<String, Point> points = new LinkedHashMap<>();
        private final List<String> conflicts = new ArrayList<>();
        public Map<String, Point> getPoints() { return points; }
        public List<String> getConflicts() { return conflicts; }
    }

    public static class Point {
        String code;
        String coordinateText;
        String sourcePointType;
        String relevantFlag;
        String applicableAirports;
        String pilotFlag;
        String dtiFlag;
        String tfmFlag;
        String comment;
        int lineNumber;
        double latitude;
        double longitude;

        boolean sameDefinition(Point other) {
            return coordinateText.equals(other.coordinateText) && sourcePointType.equals(other.sourcePointType)
                    && relevantFlag.equals(other.relevantFlag) && applicableAirports.equals(other.applicableAirports)
                    && pilotFlag.equals(other.pilotFlag) && dtiFlag.equals(other.dtiFlag)
                    && tfmFlag.equals(other.tfmFlag) && comment.equals(other.comment);
        }
    }

    public static class Route {
        String code;
        String sense;
        String cruiseLevelRule;
        String rnavCapability;
        String rnavCapabilityPost2012;
        String rnpCapabilityPost2012;
        String rvsmLevel;
        String routeType;
        String procedureAirport;
        String procedureProfile;
        String procedureRunway;
        String procedureDirection;
        String procedureOperation;
        String eligibleRoute;
        int lineNumber;
        final List<String> pointCodes = new ArrayList<>();
    }
}
