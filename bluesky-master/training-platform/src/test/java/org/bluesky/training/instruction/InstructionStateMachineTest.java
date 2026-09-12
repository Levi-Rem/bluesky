package org.bluesky.training.instruction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P09：指令状态机全合法/非法边（详细设计 2.2 §5.4）。 */
class InstructionStateMachineTest {

    private static final Map<String, String> LEGAL_EDGES = buildLegalEdges();

    private static Map<String, String> buildLegalEdges() {
        Map<String, String> edges = new LinkedHashMap<>();
        edges.put("RECEIVED+VALIDATE", "VALIDATED");
        edges.put("RECEIVED+REJECT", "REJECTED");
        edges.put("VALIDATED+BLOCK", "BLOCKED");
        edges.put("VALIDATED+DIRECT_EXECUTE", "EXECUTING");
        edges.put("VALIDATED+BEGIN_DISPATCH", "DISPATCHING");
        edges.put("VALIDATED+REJECT", "REJECTED");
        edges.put("BLOCKED+UNBLOCK", "DISPATCHING");
        edges.put("BLOCKED+CANCEL", "CANCELLED");
        edges.put("DISPATCHING+CONFIRM_APPLIED", "EXECUTING");
        edges.put("DISPATCHING+FAIL", "FAILED");
        edges.put("DISPATCHING+TIME_OUT", "TIMED_OUT");
        edges.put("EXECUTING+COMPLETE", "COMPLETED");
        edges.put("EXECUTING+REPLACE", "REPLACED");
        edges.put("EXECUTING+FAIL", "FAILED");
        edges.put("EXECUTING+TIME_OUT", "TIMED_OUT");
        edges.put("EXECUTING+CANCEL", "CANCELLED");
        return edges;
    }

    private final InstructionStateMachine machine = new InstructionStateMachine();

    @Test
    void givenEveryLegalEdgeWhenTransitionedThenTargetMatches() {
        for (Map.Entry<String, String> legal : LEGAL_EDGES.entrySet()) {
            String from = legal.getKey().substring(0, legal.getKey().indexOf('+'));
            String event = legal.getKey().substring(legal.getKey().indexOf('+') + 1);
            assertEquals(legal.getValue(), machine.transition(from, event),
                    from + " + " + event + " → " + legal.getValue());
        }
    }

    @Test
    void givenEveryIllegalEdgeWhenTransitionedThenRejected() {
        List<String> states = Arrays.asList(InstructionStateMachine.STATES);
        List<String> events = Arrays.asList("VALIDATE", "BLOCK", "UNBLOCK", "DIRECT_EXECUTE",
                "BEGIN_DISPATCH", "CONFIRM_APPLIED", "COMPLETE", "REPLACE", "FAIL", "TIME_OUT",
                "CANCEL", "REJECT");
        int rejected = 0;
        for (String state : states) {
            for (String event : events) {
                if (LEGAL_EDGES.containsKey(state + "+" + event)) {
                    continue;
                }
                try {
                    machine.transition(state, event);
                } catch (IllegalStateException expected) {
                    rejected++;
                }
            }
        }
        assertEquals(states.size() * events.size() - LEGAL_EDGES.size(), rejected,
                "全部非法边必须被拒绝");
    }

    @Test
    void givenAdapterlessCommandWhenValidatedThenDirectExecuteAllowed() {
        assertEquals("EXECUTING", machine.transition("VALIDATED", "DIRECT_EXECUTE"));
        // 纯业务字段指令同事务可直达 COMPLETED（详细设计 2.2 §5.4）
        assertEquals("COMPLETED", machine.transition("EXECUTING", "COMPLETE"));
    }

    @Test
    void givenWaitingOrPendingWhenTransitionedThenAlwaysRejected() {
        for (String event : Arrays.asList("VALIDATE", "BEGIN_DISPATCH", "COMPLETE", "CANCEL")) {
            assertThrows(IllegalStateException.class, () -> machine.transition("WAITING", event));
            assertThrows(IllegalStateException.class, () -> machine.transition("PENDING", event));
        }
    }

    @Test
    void givenCompletedInstructionWhenOverriddenThenStaysCompleted() {
        // 完成指令不可 REPLACE/CANCEL（详细设计 6.2）
        assertThrows(IllegalStateException.class,
                () -> machine.transition("COMPLETED", "REPLACE"));
        assertThrows(IllegalStateException.class,
                () -> machine.transition("COMPLETED", "CANCEL"));
        assertTrue(InstructionStateMachine.isTerminal("COMPLETED"));
        assertFalse(InstructionStateMachine.isTerminal("EXECUTING"));
    }
}
