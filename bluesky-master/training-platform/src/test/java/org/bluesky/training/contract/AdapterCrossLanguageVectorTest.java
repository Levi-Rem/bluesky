package org.bluesky.training.contract;

import org.bluesky.training.testsupport.AdapterProtocolFixture;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P00：Java/Python 共用协议向量。
 * 同一信封在两侧的校验结论必须一致；Java 侧在本测试内校验，
 * Python 侧由 scripts/verify_v2_contracts.py --vectors 执行。
 */
class AdapterCrossLanguageVectorTest {

    @Test
    void givenSameEnvelopeWhenValidatedByJavaAndPythonThenResultMatches() throws Exception {
        List<Map<String, Object>> vectors = AdapterProtocolFixture.sharedVectors();
        assertFalse(vectors.isEmpty(), "共享向量文件必须包含向量");

        for (Map<String, Object> vector : vectors) {
            boolean expectValid = (Boolean) vector.get("expectValid");

            List<String> problems = AdapterProtocolFixture.validateVector(vector);
            assertEquals(expectValid, problems.isEmpty(),
                    () -> vector.get("name") + " 的 Java 校验结论与预期不一致: " + problems);
        }

        assumeTrue(AdapterProtocolFixture.pythonAvailable(), "当前环境无 python，跳过 Python 侧比对");
        int exitCode = AdapterProtocolFixture.runPythonVectorVerification();
        assertEquals(0, exitCode, "Python 侧向量校验失败，详见 scripts/verify_v2_contracts.py 输出");
    }

    @Test
    void givenFixtureBuiltEnvelopesWhenValidatedThenAllValid() {
        Map<String, Object> hello = AdapterProtocolFixture.request(
                "HELLO", "group-1", "engine-1", "hello-key");
        assertEquals(Collections.emptyList(), AdapterProtocolFixture.validateEnvelope(hello));

        Map<String, Object> apply = AdapterProtocolFixture.request(
                "INSTRUCTION_APPLY", "group-1", "engine-1", "instruction-uuid");
        Map<String, Object> applied = AdapterProtocolFixture.response(apply, "INSTRUCTION_APPLIED", true);
        assertEquals(Collections.emptyList(), AdapterProtocolFixture.validateEnvelope(applied));

        Map<String, Object> chunk = AdapterProtocolFixture.withSequence(
                AdapterProtocolFixture.event("STATE_SNAPSHOT_CHUNK", "group-1", "engine-1", 88L), 89L);
        assertEquals(Collections.emptyList(), AdapterProtocolFixture.validateEnvelope(chunk));

        assertNotNull(chunk.get("sequence"));
        assertEquals(89L, ((Number) chunk.get("sequence")).longValue());
        assertNull(AdapterProtocolFixture.request("HELLO", "group-1", "engine-1", "k").get("correlationRequestId"));
        assertEquals(apply.get("requestId"), applied.get("correlationRequestId"),
                "RESPONSE 必须复制 REQUEST 的 requestId 作为 correlationRequestId");
    }
}
