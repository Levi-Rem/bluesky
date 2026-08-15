package org.bluesky.training.persistence;

public class InstructionRow {
    private String id;
    private String exerciseAircraftId;
    private String rawText;
    private String instructionType;
    private String controlChannel;
    private String insertionMode;
    private String status;
    private long sequenceNumber;
    private String parsedPayload;
    private String failureCode;
    private String failureMessage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getExerciseAircraftId() { return exerciseAircraftId; }
    public void setExerciseAircraftId(String exerciseAircraftId) { this.exerciseAircraftId = exerciseAircraftId; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getInstructionType() { return instructionType; }
    public void setInstructionType(String instructionType) { this.instructionType = instructionType; }
    public String getControlChannel() { return controlChannel; }
    public void setControlChannel(String controlChannel) { this.controlChannel = controlChannel; }
    public String getInsertionMode() { return insertionMode; }
    public void setInsertionMode(String insertionMode) { this.insertionMode = insertionMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public String getParsedPayload() { return parsedPayload; }
    public void setParsedPayload(String parsedPayload) { this.parsedPayload = parsedPayload; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
