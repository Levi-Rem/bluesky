package org.bluesky.training.adapter;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P04：2.0 信封编解码与 1 MiB 限制（详细设计 10.1）。 */
class ProtocolEnvelopeCodecTest {

    private final ProtocolEnvelopeCodec codec = new ProtocolEnvelopeCodec();

    private Map<String, Object> envelope(String protocolVersion) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", protocolVersion);
        envelope.put("messageKind", "REQUEST");
        envelope.put("messageType", "HELLO");
        envelope.put("exerciseGroupId", "group-1");
        envelope.put("engineInstanceId", "engine-1");
        envelope.put("requestId", "req-1");
        envelope.put("correlationRequestId", null);
        envelope.put("idempotencyKey", "key-1");
        envelope.put("sequence", 1);
        envelope.put("systemTimeUtc", "2026-08-31T09:00:00.000Z");
        envelope.put("simulationTimeSeconds", 0.0);
        envelope.put("payloadSchemaVersion", "hello/1");
        envelope.put("payload", new LinkedHashMap<String, Object>());
        return envelope;
    }

    @Test
    void givenValidEnvelopeWhenRoundTrippedThenFieldsSurvive() {
        Map<String, Object> original = envelope("2.0");

        byte[] encoded = codec.encode(original);
        Map<String, Object> decoded = codec.decode(encoded);

        assertEquals(original.get("messageType"), decoded.get("messageType"));
        assertEquals(original.get("sequence"), decoded.get("sequence"));
        assertEquals("2.0", decoded.get("protocolVersion"));
        assertTrue(encoded.length <= ProtocolEnvelopeCodec.MAX_FRAME_BYTES);
    }

    @Test
    void givenStaleMajorVersionWhenDecodedThenRejected() {
        byte[] stale = codec.frameOf(new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(envelope("1.0")).toString());

        AdapterProtocolException failure = assertThrows(AdapterProtocolException.class,
                () -> codec.decode(stale));
        assertEquals("PROTOCOL_VERSION_MISMATCH", failure.code());
    }

    @Test
    void givenSchemaViolationsWhenEncodedThenRejected() {
        Map<String, Object> invalidKind = envelope("2.0");
        invalidKind.put("messageKind", "COMMAND");
        assertEquals("ENVELOPE_INVALID", assertThrows(AdapterProtocolException.class,
                () -> codec.encode(invalidKind)).code());

        Map<String, Object> invalidType = envelope("2.0");
        invalidType.put("messageType", "UNKNOWN_MESSAGE");
        assertEquals("ENVELOPE_INVALID", assertThrows(AdapterProtocolException.class,
                () -> codec.encode(invalidType)).code());

        Map<String, Object> invalidSequence = envelope("2.0");
        invalidSequence.put("sequence", 1.5d);
        assertEquals("ENVELOPE_INVALID", assertThrows(AdapterProtocolException.class,
                () -> codec.encode(invalidSequence)).code());

        Map<String, Object> invalidPayload = envelope("2.0");
        invalidPayload.put("payload", java.util.Collections.singletonList("not-an-object"));
        assertEquals("ENVELOPE_INVALID", assertThrows(AdapterProtocolException.class,
                () -> codec.encode(invalidPayload)).code());
    }

    @Test
    void givenFrameOverOneMiBWhenValidatedThenRejected() {
        byte[] oversized = new byte[ProtocolEnvelopeCodec.MAX_FRAME_BYTES + 1];

        AdapterProtocolException failure = assertThrows(AdapterProtocolException.class,
                () -> codec.validateSize(oversized));
        assertEquals("FRAME_TOO_LARGE", failure.code());
    }

    @Test
    void givenEmptyBodyWhenDecodedThenRejected() {
        assertThrows(AdapterProtocolException.class, () -> codec.decode(new byte[0]));
        assertThrows(AdapterProtocolException.class, () -> codec.decode(null));
    }

    @Test
    void givenMissingRequiredFieldsWhenEncodedThenRejected() {
        Map<String, Object> incomplete = envelope("2.0");
        incomplete.remove("messageType");

        AdapterProtocolException failure = assertThrows(AdapterProtocolException.class,
                () -> codec.encode(incomplete));
        assertEquals("ENVELOPE_INCOMPLETE", failure.code());
    }

    @Test
    void givenSharedVectorsWhenRoundTrippedThenVersionGovernsAcceptance() {
        for (Map<String, Object> vector : org.bluesky.training.testsupport.AdapterProtocolFixture
                .sharedVectors()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) vector.get("envelope");
            boolean version20 = "2.0".equals(envelope.get("protocolVersion"));
            try {
                Map<String, Object> decoded = codec.decode(codec.encode(envelope));
                assertTrue(version20, vector.get("name") + " 1.x 信封不应通过编解码");
                assertEquals(envelope.get("messageType"), decoded.get("messageType"));
            } catch (AdapterProtocolException e) {
                assertTrue(!version20, vector.get("name") + " 不应失败: " + e.getMessage());
            }
        }
    }
}
