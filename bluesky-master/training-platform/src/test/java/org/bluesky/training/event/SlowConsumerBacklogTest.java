package org.bluesky.training.event;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlowConsumerBacklogTest {
    @Test void blockedTransportDisconnectsOnlyWhenPendingEventsExceedLimit() throws Exception {
        TerminalDeliveryService deliveries=mock(TerminalDeliveryService.class);
        StreamEpochService epochs=mock(StreamEpochService.class);
        when(epochs.currentEpoch()).thenReturn("epoch");
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("terminalId","T1");row.put("deliverySequence",1L);row.put("eventType","message.received");row.put("payload","{}");
        when(deliveries.loadAfter(eq("T1"),eq("epoch"),eq(0L),anyInt())).thenReturn(Collections.singletonList(row));
        CountDownLatch sending=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        SseEmitter emitter=new SseEmitter(0L) {
            @Override public void send(SseEventBuilder event) throws java.io.IOException {
                sending.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            @Override public void completeWithError(Throwable error) { closed.countDown(); }
        };
        ReliableEventStreamService service=new ReliableEventStreamService(deliveries,epochs) {
            @Override protected SseEmitter createEmitter() { return emitter; }
        };
        try {
            service.connect("T1",0);
            assertTrue(sending.await(2,TimeUnit.SECONDS));
            for(int i=0;i<100;i++)service.publish(row);
            assertEquals(1,closed.getCount());
            for(int i=100;i<=ReliableEventStreamService.SLOW_CONSUMER_EVENT_LIMIT;i++)service.publish(row);
            assertTrue(closed.await(1,TimeUnit.SECONDS));
        } finally { release.countDown();service.close(); }
    }
}
