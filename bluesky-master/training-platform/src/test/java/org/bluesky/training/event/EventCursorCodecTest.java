package org.bluesky.training.event;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P06：eventId 固定格式与终端校验（详细设计 9.5.2）。 */
class EventCursorCodecTest {

    @Test
    void givenEventIdWhenEncodedAndDecodedThenFieldsSurvive() {
        String eventId = EventCursorCodec.encode("PP-01", "stream-abc", 1024);

        assertEquals("PP-01:stream-abc:1024", eventId);
        Map<String, Object> decoded = EventCursorCodec.decode(eventId);
        assertEquals("PP-01", decoded.get("terminalId"));
        assertEquals("stream-abc", decoded.get("streamEpoch"));
        assertEquals(1024L, decoded.get("deliverySequence"));
    }

    @Test
    void givenMalformedCursorWhenDecodedThen409() {
        V2DomainException malformed = assertThrows(V2DomainException.class,
                () -> EventCursorCodec.decode("just-an-id"));
        assertEquals(409, malformed.httpStatus());
        assertEquals("EVENT_CURSOR_EXPIRED", malformed.code());

        assertThrows(V2DomainException.class, () -> EventCursorCodec.decode("a:b:notANumber"));
        assertThrows(V2DomainException.class, () -> EventCursorCodec.decode(null));
    }

    @Test
    void givenForeignTerminalCursorWhenValidatedThen403() {
        String eventId = EventCursorCodec.encode("PP-01", "stream-abc", 5);

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> EventCursorCodec.validateTerminal(eventId, "PP-02"));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());

        EventCursorCodec.validateTerminal(eventId, "PP-01");
    }
}
