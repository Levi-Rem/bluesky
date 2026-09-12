package org.bluesky.training.persistence;

import java.math.BigDecimal;

/** P02：终端管理行（workstation_terminal + V8 扩展列）。 */
public class TerminalAdminRow {

    private String id;
    private String name;
    private String exerciseGroupId;
    private BigDecimal frequency;
    private String unitMode;
    private boolean enabled;
    private long revision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExerciseGroupId() {
        return exerciseGroupId;
    }

    public void setExerciseGroupId(String exerciseGroupId) {
        this.exerciseGroupId = exerciseGroupId;
    }

    public BigDecimal getFrequency() {
        return frequency;
    }

    public void setFrequency(BigDecimal frequency) {
        this.frequency = frequency;
    }

    public String getUnitMode() {
        return unitMode;
    }

    public void setUnitMode(String unitMode) {
        this.unitMode = unitMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
