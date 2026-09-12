package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P01：幂等执行（详细设计 9.1 / 5.0.5）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService service;

    private static CallerContext caller() {
        return CallerContext.terminal("PP-01", "group-" + UUID.randomUUID(), "fp-digest");
    }

    private static IdempotencyCommand command(CallerContext caller, String key, String body) {
        return new IdempotencyCommand(
                caller, "POST", "/api/v2/probe",
                IdempotencyScopeFactory.create(caller, "POST", "/api/v2/probe", key),
                key, RequestDigest.sha256("POST", "/api/v2/probe", body.getBytes()),
                201, 24);
    }

    @Test
    void givenSameScopeAndDigestWhenRetriedThenStoredResponseIsReturned() {
        CallerContext caller = caller();
        String key = "stable-key";
        IdempotencyCommand command = command(caller, key, "{\"v\":1}");
        AtomicInteger executions = new AtomicInteger();

        IdempotentResult first = service.execute(command, () -> {
            executions.incrementAndGet();
            return "first-body";
        });
        IdempotentResult replay = service.execute(command, () -> {
            executions.incrementAndGet();
            return "second-body";
        });

        assertEquals(1, executions.get(), "同作用域同摘要重试必须直接重放存储响应");
        assertTrue(!first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.responseBody(), replay.responseBody());
        assertEquals(first.httpStatus(), replay.httpStatus());
    }

    @Test
    void givenSameKeyDifferentDigestThenConflict() {
        CallerContext caller = caller();
        String key = "conflict-key";
        service.execute(command(caller, key, "{\"v\":1}"), () -> "ok");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> service.execute(command(caller, key, "{\"v\":2}"), () -> "other"));
        assertEquals(409, failure.httpStatus());
        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code());
    }

    @Test
    void givenConcurrentSameKeyThenActionRunsOnce() throws Exception {
        CallerContext caller = caller();
        String key = "concurrent-key-" + UUID.randomUUID();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        List<IdempotentResult> results = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    IdempotencyCommand command = command(caller, key, "{\"v\":1}");
                    results.add(service.execute(command, () -> {
                        executions.incrementAndGet();
                        return "side-effect";
                    }));
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发执行超时");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads, results.size());
        assertEquals(1, executions.get(), "并发同键只能产生一个业务副作用");
        String expectedBody = results.get(0).responseBody();
        for (IdempotentResult result : results) {
            assertEquals(expectedBody, result.responseBody());
        }
        assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
    }

    @Test
    void givenIllegalFirstStateWhenInsertedThenDatabaseRejects() {
        assertThrows(DataIntegrityViolationException.class,
                () -> service.execute(
                        new IdempotencyCommand(caller(), "bad", "/p",
                                "scope-" + UUID.randomUUID(), "bad-key", "digest", 999, 24),
                        () -> "never"),
                "非法首次状态必须被数据库约束拒绝");
    }
}
