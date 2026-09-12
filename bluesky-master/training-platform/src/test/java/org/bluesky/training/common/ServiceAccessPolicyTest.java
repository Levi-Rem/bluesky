package org.bluesky.training.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P02：服务身份用途隔离（详细设计 9.1、14.8）。 */
class ServiceAccessPolicyTest {

    private final ServiceAccessPolicy policy = new ServiceAccessPolicy();

    @Test
    void givenTerminalCertificateWhenEndingExerciseThenForbidden() {
        CallerContext terminal = CallerContext.terminal("PP-01", "group-1", "digest");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireOrchestrator(terminal));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());

        V2DomainException opsFailure = assertThrows(V2DomainException.class,
                () -> policy.requireOperations(terminal));
        assertEquals("TRUSTED_IDENTITY_REJECTED", opsFailure.code());
    }

    @Test
    void givenOrchestratorWhenSubmittingFlightInstructionThenForbidden() {
        CallerContext orchestrator = CallerContext.orchestrator("orch-1", "digest");

        // 编排身份不能提交飞行控制指令：终端写权限必须拒绝它
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> new TerminalAccessPolicy().requireTerminalWrite(orchestrator));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());

        assertDoesNotThrow(() -> policy.requireOrchestrator(orchestrator));
    }

    @Test
    void givenOperationsIdentityWhenManagingProfilesThenAllowed() {
        CallerContext operations = CallerContext.operations("ops-1", "digest");

        assertDoesNotThrow(() -> policy.requireOperations(operations));

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireOrchestrator(operations));
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }

    @Test
    void givenMissingIdentityWhenServiceRequiredThenRejected() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> policy.requireOperations(null));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }
}
