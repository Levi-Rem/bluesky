package org.bluesky.training.event;

import org.bluesky.training.common.V2DomainException;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P06：Last-Event-ID 续传（详细设计 9.5）。
 * 游标格式错误、纪元变化或超出可靠窗口 → 409 EVENT_CURSOR_EXPIRED，客户端重新 bootstrap。
 */
@Service
public class ReliableEventStreamService {

    public static final int SLOW_CONSUMER_EVENT_LIMIT = 2_000;
    public static final int SLOW_CONSUMER_BYTES_LIMIT = 10 * 1024 * 1024;

    private final TerminalDeliveryService deliveryService;
    private final StreamEpochService epochService;
    private final Map<String, CopyOnWriteArrayList<Connection>> emitters = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService writers=java.util.concurrent.Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"sse-writer");t.setDaemon(true);return t;});

    public ReliableEventStreamService(TerminalDeliveryService deliveryService,
                                      StreamEpochService epochService) {
        this.deliveryService = deliveryService;
        this.epochService = epochService;
    }

    /** 解析并校验游标；返回游标前序号，越窗抛 409。 */
    public long prepareCursor(String terminalId, String lastEventId) {
        Map<String, Object> decoded = EventCursorCodec.decode(lastEventId);
        EventCursorCodec.validateTerminal(lastEventId, terminalId);
        String currentEpoch = epochService.currentEpoch();
        if (!currentEpoch.equals(decoded.get("streamEpoch"))) {
            throw new V2DomainException("EVENT_CURSOR_EXPIRED", 409,
                    "流纪元已重建，需要重新 bootstrap", Arrays.asList("Last-Event-ID"));
        }
        long afterSequence = (Long) decoded.get("deliverySequence");
        long oldest = deliveryService.oldestRetainedSequence(terminalId, currentEpoch);
        if (afterSequence > 0 && afterSequence < oldest - 1) {
            throw new V2DomainException("EVENT_CURSOR_EXPIRED", 409,
                    "游标超出可靠事件窗口", Arrays.asList("Last-Event-ID"));
        }
        return afterSequence;
    }

    /** 回放游标之后的可靠事件；信封带 eventId 供客户端幂等应用。 */
    public List<Map<String, Object>> replayAfterCursor(String terminalId, long afterSequence,
                                                       int limit) {
        String epoch = epochService.currentEpoch();
        List<Map<String, Object>> rows = deliveryService.loadAfter(terminalId, epoch,
                afterSequence, limit);
        for (Map<String, Object> row : rows) {
            row.put("eventId", EventCursorCodec.encode(terminalId, epoch,
                    ((Number) row.get("deliverySequence")).longValue()));
        }
        return rows;
    }

    public Map<String, Object> envelopeFor(Map<String, Object> row) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", row.get("eventId"));
        envelope.put("streamEpoch", row.get("epoch"));
        envelope.put("deliverySequence", row.get("deliverySequence"));
        envelope.put("groupSequence", row.get("groupSequence"));
        envelope.put("eventType", row.get("eventType"));
        envelope.put("exerciseGroupId", row.get("groupId"));
        envelope.put("terminalId", row.get("terminalId"));
        envelope.put("entityId", row.get("entityId"));
        envelope.put("systemTimeUtc", isoTime(row.get("systemTimeUtc")));
        envelope.put("simulationTimeSeconds", row.get("simulationTimeSeconds"));
        envelope.put("payload", rawPayload(row));
        return envelope;
    }

    /** 先注册实时连接，再全量分批回放；并发追加可能重复但 eventId 恒定，客户端可幂等去重。 */
    public SseEmitter connect(String terminalId, long afterSequence) {
        SseEmitter emitter = createEmitter();
        Connection connection = new Connection(emitter, afterSequence);
        // 注册/注销都经 compute 原子完成，杜绝"空表检查后被并发 add"的孤儿连接竞态（评审 C10）
        emitters.compute(terminalId, (key, list) -> {
            CopyOnWriteArrayList<Connection> values =
                    list != null ? list : new CopyOnWriteArrayList<>();
            values.add(connection);
            return values;
        });
        Runnable cleanup = () -> remove(terminalId, connection);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        // 先持有连接锁再暴露给实时发布线程，保证历史回放永远先于实时追赶。
        enqueue(terminalId,connection,null,false);
        return emitter;
    }

    protected SseEmitter createEmitter() { return new SseEmitter(0L); }

    /** 事务提交后的可靠实时推送。 */
    public void publish(Map<String, Object> row) {
        String terminalId = String.valueOf(row.get("terminalId"));
        for (Connection connection : emitters.getOrDefault(
                terminalId, new CopyOnWriteArrayList<>())) {
            enqueue(terminalId,connection,row,false);
        }
    }

    public void publishDynamic(String terminalId, Map<String, Object> frame) {
        for (Connection connection : emitters.getOrDefault(terminalId, new CopyOnWriteArrayList<>())) {
            enqueue(terminalId,connection,frame,true);
        }
    }

    private void enqueue(String terminal,Connection connection,Map<String,Object> row,boolean dynamic) {
        synchronized(connection) {
            if(connection.closed)return;
            if(dynamic)connection.latestDynamic=row;
            else {connection.pendingEvents++;connection.pendingBytes+=row==null?0:frameBytes(envelopeFor(row)).length;}
            if(connection.pendingEvents>SLOW_CONSUMER_EVENT_LIMIT || connection.pendingBytes>SLOW_CONSUMER_BYTES_LIMIT) {
                connection.closed=true;
                connection.emitter.completeWithError(new java.io.IOException("SLOW_CONSUMER; lastDeliverySequence="+connection.lastSentSequence));
                remove(terminal,connection);return;
            }
            if(connection.writing)return;
            connection.writing=true;
        }
        writers.execute(()->drain(terminal,connection));
    }

    private void drain(String terminal,Connection connection) {
        try {
            while(true) {
                long count,bytes;Map<String,Object> dynamic;
                synchronized(connection) {
                    if(connection.closed)return;
                    count=connection.pendingEvents;bytes=connection.pendingBytes;
                    dynamic=connection.latestDynamic;connection.latestDynamic=null;
                    if(count==0 && dynamic==null){connection.writing=false;return;}
                }
                if(count>0 && !replayAvailable(terminal,connection)){remove(terminal,connection);return;}
                if(dynamic!=null)connection.emitter.send(SseEmitter.event().name("aircraft.state.frame").data(dynamic));
                synchronized(connection){connection.pendingEvents-=count;connection.pendingBytes-=bytes;}
            }
        } catch(Exception failure) {connection.emitter.completeWithError(failure);remove(terminal,connection);}
    }

    @javax.annotation.PreDestroy public void close() {
        emitters.values().forEach(list->list.forEach(c->c.emitter.complete()));emitters.clear();writers.shutdownNow();
    }

    private boolean replayAvailable(String terminalId, Connection connection) {
        while (true) {
            List<Map<String, Object>> batch = replayAfterCursor(
                    terminalId, connection.lastSentSequence,
                    TerminalDeliveryService.REPLAY_BATCH_LIMIT);
            if (batch.isEmpty()) {
                return true;
            }
            for (Map<String, Object> item : batch) {
                if (!send(connection, item)) {
                    return false;
                }
                connection.lastSentSequence = ((Number) item.get("deliverySequence")).longValue();
            }
            if (batch.size() < TerminalDeliveryService.REPLAY_BATCH_LIMIT) {
                return true;
            }
        }
    }

    /**
     * 单连接发送并执行慢消费者断开策略（详细设计 9.5 规则 7；评审 C6）：
     * 累计事件数或字节数超限 → 发送携带最后成功序号的断开通知并主动断开，
     * 客户端以 lastDeliverySequence 重建游标续传，不静默丢事件。
     */
    private boolean send(Connection connection, Map<String, Object> row) {
        byte[] frame = frameBytes(envelopeFor(row));
        if (frame.length > SLOW_CONSUMER_BYTES_LIMIT) {
            disconnectSlowConsumer(connection);
            return false;
        }
        try {
            connection.emitter.send(SseEmitter.event()
                    .id(String.valueOf(row.get("eventId")))
                    .name(String.valueOf(row.get("eventType")))
                    .data(envelopeFor(row), MediaType.APPLICATION_JSON));
            return true;
        } catch (java.io.IOException | IllegalStateException e) {
            connection.emitter.completeWithError(e);
            return false;
        }
    }

    private void disconnectSlowConsumer(Connection connection) {
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("reason", "SLOW_CONSUMER");
        notice.put("lastDeliverySequence", connection.lastSentSequence);
        try {
            connection.emitter.send(SseEmitter.event()
                    .name("stream.disconnected")
                    .data(notice, MediaType.APPLICATION_JSON));
        } catch (java.io.IOException | IllegalStateException ignored) {
            // 客户端可能已断开；通知尽力而为，lastDeliverySequence 也可从最后事件 id 恢复
        }
        connection.emitter.complete();
    }

    private void remove(String terminalId, Connection connection) {
        synchronized(connection){connection.closed=true;connection.latestDynamic=null;}
        emitters.computeIfPresent(terminalId, (key, values) -> {
            values.remove(connection);
            return values.isEmpty() ? null : values;
        });
    }

    private static final class Connection {
        private final SseEmitter emitter;
        private volatile long lastSentSequence;
        private long pendingEvents;
        private long pendingBytes;
        private Map<String,Object> latestDynamic;
        private boolean writing;
        private boolean closed;

        private Connection(SseEmitter emitter, long lastSentSequence) {
            this.emitter = emitter;
            this.lastSentSequence = lastSentSequence;
        }
    }

    private static byte[] frameBytes(Map<String, Object> envelope) {
        try {
            return JSON.writeValueAsBytes(envelope);
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String isoTime(Object value) {
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toInstant().toString();
        }
        if (value instanceof java.time.Instant) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    private static Object rawPayload(Map<String, Object> row) {
        Object payload = row.get("payload");
        if (payload instanceof String) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue((String) payload, Object.class);
            } catch (java.io.IOException e) {
                return payload;
            }
        }
        return payload;
    }
}
