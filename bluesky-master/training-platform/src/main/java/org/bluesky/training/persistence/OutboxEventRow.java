package org.bluesky.training.persistence;

import java.sql.Timestamp;

/** outbox_event 行（V8）：业务事件或待发 Adapter 动作。 */
public class OutboxEventRow {

    public static final String KIND_BUSINESS_EVENT = "BUSINESS_EVENT";
    public static final String KIND_ADAPTER_ACTION = "ADAPTER_ACTION";

    private String id;
    private String outboxKind;
    private String exerciseGroupId;
    private String engineInstanceId;
    private String eventType;
    private String requestId;
    private String idempotencyKey;
    private String payloadChecksum;
    private String payload;
    private String status = "PENDING";
    private int attemptCount;
    private int maxAttempts = 8;
    private Timestamp nextAttemptAt;
    private String claimedBy;
    private Timestamp claimedAt;

    public static OutboxEventRow businessEvent(String id, String exerciseGroupId,
                                               String eventType, String payload) {
        OutboxEventRow row = new OutboxEventRow();
        row.id = id;
        row.outboxKind = KIND_BUSINESS_EVENT;
        row.exerciseGroupId = exerciseGroupId;
        row.eventType = eventType;
        row.payload = payload;
        row.payloadChecksum = checksum(payload);
        return row;
    }

    public static OutboxEventRow adapterAction(String id, String exerciseGroupId,
                                               String actionType, String payload) {
        OutboxEventRow row = new OutboxEventRow();
        row.id = id;
        row.outboxKind = KIND_ADAPTER_ACTION;
        row.exerciseGroupId = exerciseGroupId;
        row.eventType = actionType;
        row.payload = payload;
        row.payloadChecksum = checksum(payload);
        return row;
    }

    public OutboxEventRow routeTo(String engineInstanceId, String requestId,
                                  String idempotencyKey) {
        this.engineInstanceId = engineInstanceId;
        this.requestId = requestId;
        this.idempotencyKey = idempotencyKey;
        return this;
    }

    public String getId() {
        return id;
    }

    public String getOutboxKind() {
        return outboxKind;
    }

    public String getExerciseGroupId() {
        return exerciseGroupId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEngineInstanceId() {
        return engineInstanceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayloadChecksum() {
        return payloadChecksum;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Timestamp getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Timestamp getClaimedAt() {
        return claimedAt;
    }

    private static String checksum(String payload) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(payload)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte part : bytes) {
                value.append(String.format("%02x", part & 0xff));
            }
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
