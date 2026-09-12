package org.bluesky.training.exercise;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P05：训练组状态机唯一转换定义（详细设计 2.2 §5.1，含 STARTING→READY 回退边）。 */
class ExerciseGroupStateMachineTest {

    private static final Map<String, String> LEGAL_EDGES = buildLegalEdges();

    private static Map<String, String> buildLegalEdges() {
        Map<String, String> edges = new LinkedHashMap<>();
        edges.put(edge("READY", "REQUEST_START"), "STARTING");
        edges.put(edge("STARTING", "CONFIRM_STARTED"), "RUNNING");
        edges.put(edge("STARTING", "FAIL_START"), "READY");
        edges.put(edge("RUNNING", "REQUEST_PAUSE"), "PAUSING");
        edges.put(edge("PAUSING", "CONFIRM_PAUSED"), "PAUSED");
        edges.put(edge("PAUSED", "REQUEST_RESUME"), "RESUMING");
        edges.put(edge("RESUMING", "CONFIRM_RESUMED"), "RUNNING");
        edges.put(edge("RUNNING", "REQUEST_END"), "ENDING");
        edges.put(edge("PAUSED", "REQUEST_END"), "ENDING");
        edges.put(edge("RECOVERY_FAILED", "REQUEST_END"), "ENDING");
        edges.put(edge("ENDING", "CONFIRM_ENDED"), "ENDED");
        edges.put(edge("STARTING", "ENTER_RECOVERING"), "RECOVERING");
        edges.put(edge("PAUSING", "ENTER_RECOVERING"), "RECOVERING");
        edges.put(edge("RESUMING", "ENTER_RECOVERING"), "RECOVERING");
        edges.put(edge("RUNNING", "ENTER_RECOVERING"), "RECOVERING");
        edges.put(edge("PAUSED", "ENTER_RECOVERING"), "RECOVERING");
        edges.put(edge("RECOVERING", "COMPLETE_RECOVERY"), "PAUSED");
        edges.put(edge("RECOVERING", "FAIL_RECOVERY"), "RECOVERY_FAILED");
        edges.put(edge("RECOVERY_FAILED", "RETRY_RECOVERY"), "RECOVERING");
        edges.put(edge("RECOVERY_FAILED", "REQUEST_END"), "ENDING");
        return edges;
    }

    private static String edge(String from, String event) {
        return from + "+" + event;
    }

    private final ExerciseGroupStateMachine machine = new ExerciseGroupStateMachine();

    @Test
    void givenEveryLegalEdgeWhenTransitionedThenTargetStateMatches() {
        for (Map.Entry<String, String> legal : LEGAL_EDGES.entrySet()) {
            String from = legal.getKey().substring(0, legal.getKey().indexOf('+'));
            String event = legal.getKey().substring(legal.getKey().indexOf('+') + 1);
            assertEquals(legal.getValue(), machine.transition(from, event),
                    from + " + " + event + " 必须到达 " + legal.getValue());
        }
    }

    @Test
    void givenEveryIllegalEdgeWhenTransitionedThenRejected() {
        List<String> states = Arrays.asList("READY", "STARTING", "RUNNING", "PAUSING",
                "PAUSED", "RESUMING", "RECOVERING", "RECOVERY_FAILED", "ENDING", "ENDED");
        List<String> events = Arrays.asList("REQUEST_START", "CONFIRM_STARTED", "FAIL_START",
                "REQUEST_PAUSE", "CONFIRM_PAUSED", "REQUEST_RESUME", "CONFIRM_RESUMED",
                "REQUEST_END", "CONFIRM_ENDED", "ENTER_RECOVERING", "COMPLETE_RECOVERY",
                "FAIL_RECOVERY", "RETRY_RECOVERY");
        int illegalRejected = 0;
        for (String state : states) {
            for (String event : events) {
                if (LEGAL_EDGES.containsKey(edge(state, event))) {
                    continue;
                }
                try {
                    machine.transition(state, event);
                } catch (IllegalStateException expected) {
                    illegalRejected++;
                }
            }
        }
        int expectedIllegal = states.size() * events.size() - LEGAL_EDGES.size();
        assertEquals(expectedIllegal, illegalRejected, "全部非法边必须被拒绝");
        assertTrue(expectedIllegal > 0);
    }

    @Test
    void givenUnknownStateOrEventWhenTransitionedThenRejected() {
        assertThrows(IllegalStateException.class, () -> machine.transition("UNKNOWN", "REQUEST_START"));
        assertThrows(IllegalStateException.class, () -> machine.transition("READY", "MAGIC"));
        assertThrows(IllegalStateException.class, () -> machine.transition(null, "REQUEST_START"));
    }

    @Test
    void givenEndedStateWhenAnyActionTriedThenAlwaysRejected() {
        for (String event : Arrays.asList("REQUEST_START", "REQUEST_PAUSE", "REQUEST_RESUME",
                "REQUEST_END", "ENTER_RECOVERING")) {
            assertThrows(IllegalStateException.class,
                    () -> machine.transition("ENDED", event),
                    "ENDED 永久只读: " + event);
        }
    }
}
