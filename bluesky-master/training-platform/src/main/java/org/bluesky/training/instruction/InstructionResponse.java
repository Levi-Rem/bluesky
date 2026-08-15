package org.bluesky.training.instruction;

import org.bluesky.training.persistence.InstructionRow;

public final class InstructionResponse {
    private final String id;
    private final String aircraftId;
    private final String text;
    private final String type;
    private final String insertion;
    private final String status;
    private final long sequenceNumber;
    private final String failureCode;
    private final String failureMessage;

    public InstructionResponse(InstructionRow row) {
        this.id = row.getId();
        this.aircraftId = row.getExerciseAircraftId();
        this.text = row.getRawText();
        this.type = row.getInstructionType();
        this.insertion = row.getInsertionMode();
        this.status = row.getStatus();
        this.sequenceNumber = row.getSequenceNumber();
        this.failureCode = row.getFailureCode();
        this.failureMessage = row.getFailureMessage();
    }

    public String getId() { return id; }
    public String getAircraftId() { return aircraftId; }
    public String getText() { return text; }
    public String getType() { return type; }
    public String getInsertion() { return insertion; }
    public String getStatus() { return status; }
    public long getSequenceNumber() { return sequenceNumber; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
}
