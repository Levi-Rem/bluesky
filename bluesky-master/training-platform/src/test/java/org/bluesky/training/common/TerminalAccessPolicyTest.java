package org.bluesky.training.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P02：终端通用权限（详细设计 6.4、14.7）。 */
class TerminalAccessPolicyTest {

    private final TerminalAccessPolicy policy = new TerminalAccessPolicy();

    @Test
    void givenTerminalFromOtherGroupThenTerminalNotInGroup() {
        CallerContext caller = CallerContext.terminal("PP-01", "group-1", "digest");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireSameGroup(caller, "group-2"));
        assertEquals(403, failure.httpStatus());
        assertEquals("TERMINAL_NOT_IN_GROUP", failure.code());

        V2DomainException anonymous = assertThrows(V2DomainException.class,
                () -> policy.requireSameGroup(null, "group-1"));
        assertEquals("TRUSTED_IDENTITY_REJECTED", anonymous.code());

        assertDoesNotThrow(() -> policy.requireSameGroup(caller, "group-1"));
    }

    @Test
    void givenNonResponsibleTerminalThenAircraftNotAssigned() {
        CallerContext caller = CallerContext.terminal("PP-01", "group-1", "digest");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireResponsibleTerminal(caller, "PP-02"));
        assertEquals(403, failure.httpStatus());
        assertEquals("AIRCRAFT_NOT_ASSIGNED", failure.code());

        assertDoesNotThrow(() -> policy.requireResponsibleTerminal(caller, "PP-01"));
    }

    @Test
    void givenServiceIdentityWhenTerminalWriteRequiredThenRejected() {
        CallerContext orchestrator = CallerContext.orchestrator("orch-1", "digest");
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireTerminalWrite(orchestrator));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());

        assertDoesNotThrow(() -> policy.requireTerminalWrite(
                CallerContext.terminal("PP-01", "group-1", "digest")));
    }

    @Test
    void givenGroupStatesWhenCheckedThenOnlyAllowedStatesPass() {
        assertDoesNotThrow(() -> policy.requireGroupStateAllows("RUNNING",
                Arrays.asList("RUNNING", "PAUSED")));
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireGroupStateAllows("READY", Arrays.asList("RUNNING", "PAUSED")));
        assertEquals(409, failure.httpStatus());
        assertEquals("TRAINING_STATE_INVALID", failure.code());
    }
}
