package org.bluesky.training.event;

import org.bluesky.training.common.GroupSequenceAllocator;
import org.bluesky.training.persistence.BusinessEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * P06：可靠业务事件追加与终端扇出（详细设计 9.5）。
 * groupSequence 组内单调；每目标终端分配连续 deliverySequence；
 * 正文只投递目标终端（targetTerminalIds 为空时投递组内全部启用终端）。
 */
@Service
public class BusinessEventService {

    private final BusinessEventMapper mapper;
    private final GroupSequenceAllocator sequenceAllocator;
    private final StreamEpochService epochService;
    private final ReliableEventStreamService streamService;

    public BusinessEventService(BusinessEventMapper mapper,
                                GroupSequenceAllocator sequenceAllocator,
                                StreamEpochService epochService,
                                ReliableEventStreamService streamService) {
        this.mapper = mapper;
        this.sequenceAllocator = sequenceAllocator;
        this.epochService = epochService;
        this.streamService = streamService;
    }

    @Transactional
    public Map<String, Object> append(String groupId, String eventType, String payloadJson,
                                      List<String> targetTerminalIds) {
        Double simulationTime = mapper.findGroupSimulationTime(groupId);
        return appendInternal(null, groupId, eventType, null, payloadJson,
                simulationTime == null ? 0.0 : simulationTime, targetTerminalIds);
    }

    @Transactional
    public Map<String, Object> append(String groupId, String eventType, String entityId,
                                      String payloadJson, double simulationTimeSeconds,
                                      List<String> targetTerminalIds) {
        return appendInternal(null, groupId, eventType, entityId, payloadJson,
                simulationTimeSeconds, targetTerminalIds);
    }

    @Transactional
    public Map<String, Object> appendFromOutbox(String outboxId, String groupId,
                                                String eventType, String entityId,
                                                String payloadJson,
                                                List<String> targetTerminalIds) {
        Long existingSequence = mapper.findGroupSequenceBySourceOutboxId(outboxId);
        if (existingSequence != null) {
            return envelope(groupId, existingSequence, epochService.currentEpoch(), eventType);
        }
        Double simulationTime = mapper.findGroupSimulationTime(groupId);
        return appendInternal(outboxId, groupId, eventType, entityId, payloadJson,
                simulationTime == null ? 0.0 : simulationTime, targetTerminalIds);
    }

    private Map<String, Object> appendInternal(String sourceOutboxId, String groupId,
                                               String eventType, String entityId,
                                               String payloadJson, double simulationTimeSeconds,
                                               List<String> targetTerminalIds) {
        long groupSequence = sequenceAllocator.next(groupId);
        String eventId = UUID.randomUUID().toString();
        Timestamp systemTime = Timestamp.from(Instant.now());
        mapper.insertBusinessEvent(eventId, groupId, groupSequence, eventType, sourceOutboxId, entityId,
                systemTime, simulationTimeSeconds, payloadJson);

        String epoch = epochService.currentEpoch();
        List<Map<String, Object>> deliveries = new java.util.ArrayList<>();
        for (String terminalId : targetTerminalIds) {
            long deliverySequence = sequenceAllocator.next(sequenceKey(terminalId, epoch));
            mapper.insertDelivery(UUID.randomUUID().toString(), terminalId, epoch,
                    deliverySequence, eventId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", EventCursorCodec.encode(terminalId, epoch, deliverySequence));
            row.put("epoch", epoch);
            row.put("deliverySequence", deliverySequence);
            row.put("groupSequence", groupSequence);
            row.put("eventType", eventType);
            row.put("groupId", groupId);
            row.put("terminalId", terminalId);
            row.put("entityId", entityId);
            row.put("systemTimeUtc", systemTime);
            row.put("simulationTimeSeconds", simulationTimeSeconds);
            row.put("payload", payloadJson);
            deliveries.add(row);
        }
        publishAfterCommit(deliveries);
        return envelope(groupId, groupSequence, epoch, eventType);
    }

    @Transactional
    public List<Map<String, Object>> appendFanOut(String groupId, String eventType,
                                                  String payloadJson,
                                                  List<String> groupTerminalIds) {
        // 一个逻辑事件只写一条 business_event、只分配一次 groupSequence；
        // 终端差异仅体现在 deliverySequence。
        return Collections.singletonList(append(groupId, eventType, payloadJson,
                groupTerminalIds));
    }

    static String sequenceKey(String terminalId, String epoch) {
        // 复用 group_sequence 表：终端+纪元维度严格递增
        return "delivery:" + epoch + ":" + terminalId;
    }

    private static Map<String, Object> envelope(String groupId, long groupSequence,
                                                String epoch, String eventType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exerciseGroupId", groupId);
        body.put("groupSequence", groupSequence);
        body.put("streamEpoch", epoch);
        body.put("eventType", eventType);
        return body;
    }

    private void publishAfterCommit(List<Map<String, Object>> deliveries) {
        Runnable publish = () -> {
            for (Map<String, Object> row : deliveries) {
                streamService.publish(row);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    });
        } else {
            publish.run();
        }
    }
}
