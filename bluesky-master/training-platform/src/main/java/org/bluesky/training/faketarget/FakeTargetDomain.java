package org.bluesky.training.faketarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P16：假目标固定状态转换与运动推演（详细设计 2.2 §5.6）。
 * RADAR_SYNTHETIC 由 Java 调度器恒速大圆推演；SIMULATED_AIRCRAFT 复用航空器生命周期。
 */
public class FakeTargetDomain {

    public static final List<String> STATES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("SCHEDULED", "ACTIVE", "STOPPED", "EXPIRED",
                    "DELETE_REQUESTED", "DELETED", "FAILED"));

    private static final Map<String, String> TRANSITIONS = buildTransitions();

    private static Map<String, String> buildTransitions() {
        Map<String, String> transitions = new HashMap<>();
        transitions.put("SCHEDULED+ACTIVATE", "ACTIVE");
        transitions.put("SCHEDULED+FAIL", "FAILED");
        transitions.put("ACTIVE+STOP", "STOPPED");
        transitions.put("ACTIVE+EXPIRE", "EXPIRED");
        transitions.put("ACTIVE+REQUEST_DELETE", "DELETE_REQUESTED");
        transitions.put("STOPPED+REQUEST_DELETE", "DELETE_REQUESTED");
        transitions.put("EXPIRED+REQUEST_DELETE", "DELETE_REQUESTED");
        transitions.put("STOPPED+EXPIRE", "EXPIRED");
        transitions.put("DELETE_REQUESTED+CONFIRM_DELETE", "DELETED");
        transitions.put("DELETE_REQUESTED+FAIL", "FAILED");
        transitions.put("FAILED+REQUEST_DELETE", "DELETED");
        return transitions;
    }

    public static String transition(String current, String event) {
        if (current == null || event == null) {
            throw new IllegalStateException("状态与事件都不能为空");
        }
        String target = TRANSITIONS.get(current + "+" + event);
        if (target == null) {
            throw new IllegalStateException("非法假目标状态转换: " + current + " + " + event);
        }
        return target;
    }

    public static boolean isTerminal(String state) {
        return "DELETED".equals(state) || "EXPIRED".equals(state) || "FAILED".equals(state);
    }

    /** STOPPED 不可重启（详细设计 5.6：第二版不提供重新启动动作）。 */
    public static boolean restartAllowed(String state) {
        return false;
    }

    /**
     * 恒速大圆推演：每秒一步（局部平面近似足够模拟训练用途）。
     * lat/lon 度、真航向 deg、地速 kt；返回新位置与累计秒。
     */
    public static double[] advanceOneSecond(double latitudeDeg, double longitudeDeg,
                                            double trueHeadingDeg, double groundSpeedKt,
                                            double elapsedSeconds) {
        double distanceNmPerSecond = groundSpeedKt / 3600.0;
        double radian = Math.toRadians(trueHeadingDeg);
        double deltaLatNm = distanceNmPerSecond * Math.cos(radian);
        double deltaLonNm = distanceNmPerSecond * Math.sin(radian);
        double newLat = latitudeDeg + deltaLatNm / 60.0;
        double latScale = Math.max(1e-6, Math.cos(Math.toRadians(
                (latitudeDeg + newLat) / 2.0)));
        double newLon = longitudeDeg + deltaLonNm / 60.0 / latScale;
        // 经度跨 180° 回绕
        if (newLon > 180.0) {
            newLon -= 360.0;
        } else if (newLon < -180.0) {
            newLon += 360.0;
        }
        return new double[]{clamp(newLat, -90.0, 90.0), newLon, elapsedSeconds + 1.0};
    }

    /** 推演 n 秒后的位置（暂停时调用方不推进）。 */
    public static double[] projectPosition(double latitudeDeg, double longitudeDeg,
                                           double trueHeadingDeg, double groundSpeedKt,
                                           double seconds) {
        double lat=latitudeDeg,lon=longitudeDeg;
        long whole=(long)Math.floor(seconds);
        for(long i=0;i<whole;i++) {
            double[] next=advanceOneSecond(lat,lon,trueHeadingDeg,groundSpeedKt,i);
            lat=next[0];lon=next[1];
        }
        if(seconds>whole) {
            double[] next=advanceOneSecond(lat,lon,trueHeadingDeg,groundSpeedKt*(seconds-whole),whole);
            lat=next[0];lon=next[1];
        }
        return new double[]{lat,lon};
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 到期规则：RADAR_SYNTHETIC 到期转 EXPIRED 立即移除；SIMULATED_AIRCRAFT 走删除 Saga。 */
    public static List<String> expiryActionFor(String targetKind) {
        List<String> actions = new ArrayList<>();
        if ("RADAR_SYNTHETIC".equals(targetKind)) {
            actions.add("EXPIRE");
            actions.add("REMOVE_FROM_DISPLAY");
        } else if ("SIMULATED_AIRCRAFT".equals(targetKind)) {
            actions.add("REQUEST_DELETE");
            actions.add("AWAIT_ADAPTER_CONFIRM");
        } else {
            throw new IllegalArgumentException("未知假目标类型: " + targetKind);
        }
        return actions;
    }

    /** RADAR_SYNTHETIC 拒绝普通飞行指令；SIMULATED_AIRCRAFT 接受（详细设计 5.6）。 */
    public static boolean acceptsFlightInstructions(String targetKind) {
        return "SIMULATED_AIRCRAFT".equals(targetKind);
    }
}
