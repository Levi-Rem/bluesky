package org.bluesky.training.instruction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P09：复合聚合与稳定窗纯逻辑（详细设计 2.2 §5.4/§6.3）。 */
class CompositeAndCompletionLogicTest {

    private static Map<String, Object> child(String channel, boolean required, String status) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("channel", channel);
        child.put("required", required);
        child.put("status", status);
        return child;
    }

    @Test
    void givenCompositeAggregationWhenEvaluatedThenParentStateMatches() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(child("LATERAL", true, "COMPLETED"));
        children.add(child("VERTICAL", true, "EXECUTING"));
        children.add(child("SPEED", true, "COMPLETED"));
        assertEquals("EXECUTING", CompositeInstructionService.aggregateParentState(children));

        children.set(1, child("VERTICAL", true, "COMPLETED"));
        assertEquals("COMPLETED", CompositeInstructionService.aggregateParentState(children));

        children.set(0, child("LATERAL", true, "FAILED"));
        assertEquals("FAILED", CompositeInstructionService.aggregateParentState(children));
    }

    @Test
    void givenTakeoffPartialOverrideWhenAggregatedThenExecutingWithFlag() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(child("LATERAL", true, "REPLACED"));
        children.add(child("VERTICAL", true, "EXECUTING"));
        children.add(child("SPEED", true, "EXECUTING"));

        String state = CompositeInstructionService.aggregateAfterOverride(
                children, java.util.Arrays.asList("LATERAL"));
        assertEquals("EXECUTING", state, "部分接管后父项保持 EXECUTING + PARTIALLY_OVERRIDDEN");

        String allReplaced = CompositeInstructionService.aggregateAfterOverride(
                children, java.util.Arrays.asList("LATERAL", "VERTICAL", "SPEED"));
        assertEquals("REPLACED", allReplaced, "全部子项被接管时父项 REPLACED");
    }

    @Test
    void givenStableWindowJitterWhenEvaluatedThenWindowResets() {
        InstructionCompletionService.StableWindow window =
                new InstructionCompletionService.StableWindow(2.0);

        assertEquals(false, window.evaluate(true, 100.0));
        assertEquals(false, window.evaluate(true, 101.9));
        // 抖动：脱离容差 → 重置
        assertEquals(false, window.evaluate(false, 101.95));
        assertEquals(false, window.evaluate(true, 102.0));
        // 重新计窗
        assertEquals(false, window.evaluate(true, 103.9));
        assertEquals(true, window.evaluate(true, 104.0));
    }

    @Test
    void givenBudgetsWhenLookedUpThenMatchDesignDefaults() {
        assertEquals(180.0, InstructionCompletionService.defaultBudgetSeconds("HDG"));
        assertEquals(600.0, InstructionCompletionService.defaultBudgetSeconds("TAKEOFF"));
        assertEquals(900.0, InstructionCompletionService.defaultBudgetSeconds("MISSED"));
        assertEquals(1800.0, InstructionCompletionService.defaultBudgetSeconds("ILS"));
        assertEquals(30.0, InstructionCompletionService.defaultBudgetSeconds("SIDSTAR"));
        assertEquals(1800.0, InstructionCompletionService.QUEUE_WAIT_BUDGET_SECONDS);
        assertEquals(true, InstructionCompletionService.budgetExceeded(180.0, 180.0));
        assertEquals(false, InstructionCompletionService.budgetExceeded(180.0, 179.9));
    }
}
