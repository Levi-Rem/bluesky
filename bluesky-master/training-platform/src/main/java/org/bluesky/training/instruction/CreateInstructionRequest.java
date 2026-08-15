package org.bluesky.training.instruction;

public class CreateInstructionRequest {
    private String text;
    private String insertion;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getInsertion() { return insertion; }
    public void setInsertion(String insertion) { this.insertion = insertion; }
}
