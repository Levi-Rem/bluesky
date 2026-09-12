package org.bluesky.training.exercise;

import java.util.HashMap;
import java.util.Map;

/** P05：训练组唯一状态转换定义（详细设计 2.2 §5.1）。 */
public class ExerciseGroupStateMachine {

    public enum Event {
        REQUEST_START, CONFIRM_STARTED, FAIL_START,
        REQUEST_PAUSE, CONFIRM_PAUSED,
        REQUEST_RESUME, CONFIRM_RESUMED,
        REQUEST_END, CONFIRM_ENDED,
        ENTER_RECOVERING, COMPLETE_RECOVERY, FAIL_RECOVERY, RETRY_RECOVERY
    }

    private static final Map<String, String> TRANSITIONS = buildTransitions();

    private static Map<String, String> buildTransitions() {
        Map<String, String> transitions = new HashMap<>();
        transitions.put(key("READY", Event.REQUEST_START), "STARTING");
        transitions.put(key("STARTING", Event.CONFIRM_STARTED), "RUNNING");
        transitions.put(key("STARTING", Event.FAIL_START), "READY");
        transitions.put(key("RUNNING", Event.REQUEST_PAUSE), "PAUSING");
        transitions.put(key("PAUSING", Event.CONFIRM_PAUSED), "PAUSED");
        transitions.put(key("PAUSED", Event.REQUEST_RESUME), "RESUMING");
        transitions.put(key("RESUMING", Event.CONFIRM_RESUMED), "RUNNING");
        transitions.put(key("RUNNING", Event.REQUEST_END), "ENDING");
        transitions.put(key("PAUSED", Event.REQUEST_END), "ENDING");
        transitions.put(key("RECOVERY_FAILED", Event.REQUEST_END), "ENDING");
        transitions.put(key("ENDING", Event.CONFIRM_ENDED), "ENDED");
        transitions.put(key("STARTING", Event.ENTER_RECOVERING), "RECOVERING");
        transitions.put(key("PAUSING", Event.ENTER_RECOVERING), "RECOVERING");
        transitions.put(key("RESUMING", Event.ENTER_RECOVERING), "RECOVERING");
        transitions.put(key("RUNNING", Event.ENTER_RECOVERING), "RECOVERING");
        transitions.put(key("PAUSED", Event.ENTER_RECOVERING), "RECOVERING");
        transitions.put(key("RECOVERING", Event.COMPLETE_RECOVERY), "PAUSED");
        transitions.put(key("RECOVERING", Event.FAIL_RECOVERY), "RECOVERY_FAILED");
        transitions.put(key("RECOVERY_FAILED", Event.RETRY_RECOVERY), "RECOVERING");
        transitions.put(key("RECOVERY_FAILED", Event.REQUEST_END), "ENDING");
        return transitions;
    }

    private static String key(String state, Event event) {
        return state + "+" + event;
    }

    public String transition(String currentState, String eventName) {
        if (currentState == null || eventName == null) {
            throw new IllegalStateException("状态与事件都不能为空");
        }
        Event event;
        try {
            event = Event.valueOf(eventName);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("未知生命周期事件: " + eventName);
        }
        String target = TRANSITIONS.get(key(currentState, event));
        if (target == null) {
            throw new IllegalStateException(
                    "非法状态转换: " + currentState + " + " + event);
        }
        return target;
    }
}
