package org.bluesky.training.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P01：If-Match/revision 统一处理（详细设计 9.1：缺失 428，冲突 409）。 */
class RevisionGuardTest {

    private final RevisionGuard guard = new RevisionGuard();

    @Test
    void givenMissingIfMatchWhenParsingThenRevisionRequired() {
        V2DomainException missing = assertThrows(V2DomainException.class, () -> guard.requireIfMatch(null));
        assertEquals(428, missing.httpStatus());
        assertEquals("REVISION_REQUIRED", missing.code());

        V2DomainException blank = assertThrows(V2DomainException.class, () -> guard.requireIfMatch("  "));
        assertEquals("REVISION_REQUIRED", blank.code());

        V2DomainException malformed = assertThrows(V2DomainException.class, () -> guard.requireIfMatch("\"abc\""));
        assertEquals("REVISION_REQUIRED", malformed.code());
    }

    @Test
    void givenQuotedOrPlainRevisionWhenParsingThenValueReturned() {
        assertEquals(12L, guard.requireIfMatch("\"12\""));
        assertEquals(7L, guard.requireIfMatch("7"));
    }

    @Test
    void givenStaleRevisionWhenCheckingThenRevisionConflict() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> guard.requireExpected(12L, 13L));
        assertEquals(409, failure.httpStatus());
        assertEquals("REVISION_CONFLICT", failure.code());
        assertEquals(1, failure.fields().size());

        assertDoesNotThrow(() -> guard.requireExpected(5L, 5L));
    }
}
