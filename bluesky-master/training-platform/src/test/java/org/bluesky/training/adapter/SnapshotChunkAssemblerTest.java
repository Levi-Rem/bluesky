package org.bluesky.training.adapter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P04：分片快照重组：乱序、重复、缺片、checksum 错误与整组重取（详细设计 10.1.4）。 */
class SnapshotChunkAssemblerTest {

    private final SnapshotChunkAssembler assembler = new SnapshotChunkAssembler();

    private Map<String, Object> chunk(String snapshotId, int index, int count, String payload) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("snapshotId", snapshotId);
        chunk.put("chunkIndex", index);
        chunk.put("chunkCount", count);
        chunk.put("payload", payload);
        return chunk;
    }

    @Test
    void givenChunksOutOfOrderWhenAssembledThenOrderIsRestored() {
        assembler.acceptChunk(chunk("s1", 1, 3, "\"b\""));
        assertFalse(assembler.isComplete("s1"));

        assembler.acceptChunk(chunk("s1", 2, 3, "\"c\""));
        assembler.acceptChunk(chunk("s1", 0, 3, "\"a\""));

        assertTrue(assembler.isComplete("s1"));
        assertEquals("[\"a\",\"b\",\"c\"]", assembler.assemble("s1"));
    }

    @Test
    void givenDuplicateChunkWhenAcceptedAgainThenIgnored() {
        assembler.acceptChunk(chunk("s2", 0, 2, "\"a\""));
        assembler.acceptChunk(chunk("s2", 0, 2, "\"TAMPERED\""));
        assembler.acceptChunk(chunk("s2", 1, 2, "\"b\""));

        assertEquals("[\"a\",\"b\"]", assembler.assemble("s2"));
    }

    @Test
    void givenMissingChunkWhenCheckedThenNotComplete() {
        assembler.acceptChunk(chunk("s3", 0, 3, "\"a\""));
        assembler.acceptChunk(chunk("s3", 2, 3, "\"c\""));

        assertFalse(assembler.isComplete("s3"));
        assertThrows(AdapterProtocolException.class, () -> assembler.assemble("s3"));
    }

    @Test
    void givenChecksumMismatchWhenVerifiedThenWholeSnapshotIsRetried() {
        String snapshotId = "s4";
        assembler.acceptChunk(chunk(snapshotId, 0, 2, "\"a\""));
        assembler.acceptChunk(chunk(snapshotId, 1, 2, "\"b\""));

        String expectedChecksum = org.bluesky.training.reference.ReferenceSnapshotStore
                .sha256OfText("[\"a\",\"X\"]");

        AdapterProtocolException failure = assertThrows(AdapterProtocolException.class,
                () -> assembler.assembleAndVerify(snapshotId, expectedChecksum));
        assertEquals("SNAPSHOT_CHECKSUM_MISMATCH", failure.code());
        assertFalse(assembler.isComplete(snapshotId), "checksum 错误后必须丢弃整组等待重取");
    }

    @Test
    void givenMatchingChecksumWhenVerifiedThenAssembled() {
        String snapshotId = "s5";
        assembler.acceptChunk(chunk(snapshotId, 0, 2, "\"a\""));
        assembler.acceptChunk(chunk(snapshotId, 1, 2, "\"b\""));

        String expectedChecksum = org.bluesky.training.reference.ReferenceSnapshotStore
                .sha256OfText("[\"a\",\"b\"]");

        assertEquals("[\"a\",\"b\"]", assembler.assembleAndVerify(snapshotId, expectedChecksum));
    }

    @Test
    void givenIncompleteOlderThanTwoSecondsWhenExpiredThenDropped() {
        Instant now = Instant.now();
        assembler.acceptChunk(chunk("s6", 0, 2, "\"a\""));
        assertFalse(assembler.isComplete("s6"));

        assembler.expireIncomplete(now.plusSeconds(3));
        assertFalse(assembler.isComplete("s6"));

        // 整组重取：发送方必须重发全部分片
        assembler.acceptChunk(chunk("s6", 0, 2, "\"a\""));
        assembler.acceptChunk(chunk("s6", 1, 2, "\"b\""));
        assertTrue(assembler.isComplete("s6"));
    }
}
