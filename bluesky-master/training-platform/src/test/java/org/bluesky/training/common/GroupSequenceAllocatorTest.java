package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P01：训练组内严格递增序号（详细设计 5.0 group_sequence 语义）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class GroupSequenceAllocatorTest {

    @Autowired
    private GroupSequenceAllocator allocator;

    @Test
    void givenSequentialAllocationThenValuesIncreaseByOne() {
        String groupId = "group-seq-" + UUID.randomUUID();
        long first = allocator.next(groupId);
        long second = allocator.next(groupId);
        long third = allocator.next(groupId);

        assertEquals(first + 1, second);
        assertEquals(second + 1, third);
    }

    @Test
    void givenConcurrentAllocationThenSequencesAreUniqueAndDense() throws Exception {
        String groupId = "group-seq-" + UUID.randomUUID();
        int threads = 4;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<List<Long>>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                List<Long> values = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    values.add(allocator.next(groupId));
                }
                return values;
            });
        }
        List<Long> all = new ArrayList<>();
        try {
            for (Future<List<Long>> future : pool.invokeAll(tasks)) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, all.size());
        Collections.sort(all);
        for (int i = 1; i < all.size(); i++) {
            assertEquals(all.get(i - 1) + 1, (long) all.get(i), "组序号必须连续且不重复");
        }
    }
}
