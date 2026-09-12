package org.bluesky.training.common;

/** 受信调用方上下文（详细设计 14）：终端、编排或运维服务身份。 */
public final class CallerContext {

    public enum CallerType {
        TERMINAL, EXERCISE_ORCHESTRATOR, OPERATIONS
    }

    private final CallerType callerType;
    private final String callerId;
    private final String terminalId;
    private final String exerciseGroupId;
    private final String certificateFingerprintDigest;

    private CallerContext(CallerType callerType, String callerId, String terminalId,
                          String exerciseGroupId, String certificateFingerprintDigest) {
        this.callerType = callerType;
        this.callerId = callerId;
        this.terminalId = terminalId;
        this.exerciseGroupId = exerciseGroupId;
        this.certificateFingerprintDigest = certificateFingerprintDigest;
    }

    public static CallerContext terminal(String terminalId, String exerciseGroupId,
                                         String certificateFingerprintDigest) {
        return new CallerContext(CallerType.TERMINAL, terminalId, terminalId,
                exerciseGroupId, certificateFingerprintDigest);
    }

    public static CallerContext orchestrator(String serviceId, String certificateFingerprintDigest) {
        return new CallerContext(CallerType.EXERCISE_ORCHESTRATOR, serviceId, null,
                null, certificateFingerprintDigest);
    }

    public static CallerContext operations(String serviceId, String certificateFingerprintDigest) {
        return new CallerContext(CallerType.OPERATIONS, serviceId, null,
                null, certificateFingerprintDigest);
    }

    public CallerType callerType() {
        return callerType;
    }

    public String callerId() {
        return callerId;
    }

    public String terminalId() {
        return terminalId;
    }

    public String exerciseGroupId() {
        return exerciseGroupId;
    }

    public String certificateFingerprintDigest() {
        return certificateFingerprintDigest;
    }
}
