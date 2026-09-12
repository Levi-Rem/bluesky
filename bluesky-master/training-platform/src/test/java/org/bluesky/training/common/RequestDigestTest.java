package org.bluesky.training.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P01：规范请求摘要与幂等作用域（详细设计 9.1）。 */
class RequestDigestTest {

    @Test
    void givenSameRequestWhenDigestedThenValueIsStable() {
        String first = RequestDigest.sha256("POST", "/api/v2/exercise-groups/g1/aircraft",
                "{\"a\":1}".getBytes());
        String second = RequestDigest.sha256("POST", "/api/v2/exercise-groups/g1/aircraft",
                "{\"a\":1}".getBytes());

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void givenDifferentBodyMethodOrPathWhenDigestedThenValuesDiffer() {
        String base = RequestDigest.sha256("POST", "/p", "b1".getBytes());

        assertNotEquals(base, RequestDigest.sha256("POST", "/p", "b2".getBytes()));
        assertNotEquals(base, RequestDigest.sha256("PUT", "/p", "b1".getBytes()));
        assertNotEquals(base, RequestDigest.sha256("POST", "/q", "b1".getBytes()));
        assertNotEquals(base, RequestDigest.sha256("POST", "/p", null));
    }

    @Test
    void givenSameCallerMethodPathAndKeyWhenScopedThenScopeIsFixed() {
        CallerContext caller = CallerContext.terminal("PP-01", "group-1", "fp-digest");
        String scope = IdempotencyScopeFactory.create(caller, "POST", "/api/v2/x", "key-1");

        assertEquals(scope, IdempotencyScopeFactory.create(caller, "POST", "/api/v2/x", "key-1"));
        assertNotEquals(scope, IdempotencyScopeFactory.create(caller, "POST", "/api/v2/x", "key-2"));
        assertNotEquals(scope, IdempotencyScopeFactory.create(
                CallerContext.terminal("PP-02", "group-1", "fp-digest"), "POST", "/api/v2/x", "key-1"));
        assertTrue(scope.length() <= 128);
    }
}
