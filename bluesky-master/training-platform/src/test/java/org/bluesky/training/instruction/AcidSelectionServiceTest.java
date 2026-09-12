package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P10：ACID 选机（详细设计 2.2 §7.1：唯一才选中，多匹配不改选择）。 */
class AcidSelectionServiceTest {

    private final AcidSelectionService service = new AcidSelectionService();

    @Test
    void givenUniqueLastFourThenSelect() {
        Map<String, Object> result = service.selectBySuffix("3582",
                Arrays.asList("CSN3582", "CCA1873", "CSN1234"));

        assertEquals("CSN3582", result.get("selected"));
        assertEquals(1, ((List<?>) result.get("candidates")).size());
    }

    @Test
    void givenUniqueLastThreeThenSelect() {
        Map<String, Object> result = service.selectBySuffix("582",
                Arrays.asList("CSN3582", "CCA1873"));

        assertEquals("CSN3582", result.get("selected"));
    }

    @Test
    void givenMultipleMatchesThenReturnCandidatesWithoutChangingSelection() {
        Map<String, Object> result = service.selectBySuffix("234",
                Arrays.asList("CSN1234", "CCA1234", "CSN3582"));

        assertNull(result.get("selected"), "多匹配不改变选择");
        assertEquals(Arrays.asList("CSN1234", "CCA1234"), result.get("candidates"));
    }

    @Test
    void givenIllegalSuffixWhenSelectedThenRejected() {
        assertThrows(V2DomainException.class, () -> service.selectBySuffix("12",
                Arrays.asList("CSN3582")));
        assertThrows(V2DomainException.class, () -> service.selectBySuffix("12345",
                Arrays.asList("CSN3582")));
        assertThrows(V2DomainException.class, () -> service.selectBySuffix("abcd!",
                Arrays.asList("CSN3582")));
    }

    @Test
    void givenNoMatchWhenSelectedThenEmptyCandidates() {
        Map<String, Object> result = service.selectBySuffix("999", Arrays.asList("CSN3582"));
        assertNull(result.get("selected"));
        assertTrue(((List<?>) result.get("candidates")).isEmpty());
    }
}
