package org.bluesky.training.event;

import org.bluesky.training.common.V2DomainException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** P06：eventId 固定格式 terminalId:streamEpoch:deliverySequence（详细设计 9.5.2）。 */
public final class EventCursorCodec {

    private EventCursorCodec() {
    }

    public static String encode(String terminalId, String streamEpoch, long deliverySequence) {
        return terminalId + ":" + streamEpoch + ":" + deliverySequence;
    }

    public static Map<String, Object> decode(String eventId) {
        if (eventId == null) {
            throw new V2DomainException("EVENT_CURSOR_EXPIRED", 409, "游标为空");
        }
        String[] parts = eventId.split(":");
        if (parts.length != 3) {
            throw new V2DomainException("EVENT_CURSOR_EXPIRED", 409,
                    "游标格式必须是 terminalId:streamEpoch:deliverySequence",
                    Arrays.asList("Last-Event-ID"));
        }
        try {
            Map<String, Object> decoded = new LinkedHashMap<>();
            decoded.put("terminalId", parts[0]);
            decoded.put("streamEpoch", parts[1]);
            decoded.put("deliverySequence", Long.parseLong(parts[2]));
            return decoded;
        } catch (NumberFormatException e) {
            throw new V2DomainException("EVENT_CURSOR_EXPIRED", 409,
                    "游标序号必须是数字", Arrays.asList("Last-Event-ID"));
        }
    }

    public static void validateTerminal(String eventId, String terminalId) {
        if (!terminalId.equals(decode(eventId).get("terminalId"))) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "游标终端与当前终端不一致", Arrays.asList("Last-Event-ID"));
        }
    }
}
