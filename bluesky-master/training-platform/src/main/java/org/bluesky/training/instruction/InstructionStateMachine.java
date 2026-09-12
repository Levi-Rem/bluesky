package org.bluesky.training.instruction;

import java.util.HashMap;
import java.util.Map;

/**
 * P09：指令唯一状态转换（详细设计 2.2 §5.4）。
 * 11 个状态；禁止未定义的 WAITING/PENDING；纯业务字段指令 VALIDATED 直达 EXECUTING。
 */
public class InstructionStateMachine {

    public enum Event {
        VALIDATE, BLOCK, UNBLOCK, DIRECT_EXECUTE,
        BEGIN_DISPATCH, CONFIRM_APPLIED,
        COMPLETE, REPLACE, FAIL, TIME_OUT, CANCEL, REJECT
    }

    public static final String[] STATES = {
            "RECEIVED", "VALIDATED", "BLOCKED", "DISPATCHING", "EXECUTING",
            "COMPLETED", "REPLACED", "FAILED", "TIMED_OUT", "CANCELLED", "REJECTED"};

    private static final Map<String, String> TRANSITIONS = buildTransitions();

    private static Map<String, String> buildTransitions() {
        Map<String, String> transitions = new HashMap<>();
        transitions.put(key("RECEIVED", Event.VALIDATE), "VALIDATED");
        transitions.put(key("RECEIVED", Event.REJECT), "REJECTED");
        transitions.put(key("VALIDATED", Event.BLOCK), "BLOCKED");
        transitions.put(key("VALIDATED", Event.DIRECT_EXECUTE), "EXECUTING");
        transitions.put(key("VALIDATED", Event.BEGIN_DISPATCH), "DISPATCHING");
        transitions.put(key("VALIDATED", Event.REJECT), "REJECTED");
        transitions.put(key("BLOCKED", Event.UNBLOCK), "DISPATCHING");
        transitions.put(key("BLOCKED", Event.CANCEL), "CANCELLED");
        transitions.put(key("DISPATCHING", Event.CONFIRM_APPLIED), "EXECUTING");
        transitions.put(key("DISPATCHING", Event.FAIL), "FAILED");
        transitions.put(key("DISPATCHING", Event.TIME_OUT), "TIMED_OUT");
        transitions.put(key("EXECUTING", Event.COMPLETE), "COMPLETED");
        transitions.put(key("EXECUTING", Event.REPLACE), "REPLACED");
        transitions.put(key("EXECUTING", Event.FAIL), "FAILED");
        transitions.put(key("EXECUTING", Event.TIME_OUT), "TIMED_OUT");
        transitions.put(key("EXECUTING", Event.CANCEL), "CANCELLED");
        return transitions;
    }

    private static String key(String state, Event event) {
        return state + "+" + event;
    }

    public String transition(String currentState, String eventName) {
        if (currentState == null || eventName == null) {
            throw new IllegalStateException("状态与事件都不能为空");
        }
        if ("WAITING".equals(currentState) || "PENDING".equals(currentState)) {
            throw new IllegalStateException("禁止使用未定义状态: " + currentState);
        }
        Event event;
        try {
            event = Event.valueOf(eventName);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("未知指令事件: " + eventName);
        }
        String target = TRANSITIONS.get(key(currentState, event));
        if (target == null) {
            throw new IllegalStateException("非法指令状态转换: " + currentState + " + " + event);
        }
        return target;
    }

    public static boolean isTerminal(String state) {
        return "COMPLETED".equals(state) || "REPLACED".equals(state) || "FAILED".equals(state)
                || "TIMED_OUT".equals(state) || "CANCELLED".equals(state)
                || "REJECTED".equals(state);
    }
}
