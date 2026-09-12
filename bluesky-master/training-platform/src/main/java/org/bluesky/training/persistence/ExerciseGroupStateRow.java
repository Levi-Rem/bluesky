package org.bluesky.training.persistence;

/** exercise_group v2 状态行。 */
public class ExerciseGroupStateRow {

    private String id;
    private String name;
    private String state;
    private String stateReason;
    private long simulationTimeSeconds;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateReason() {
        return stateReason;
    }

    public void setStateReason(String stateReason) {
        this.stateReason = stateReason;
    }

    public long getSimulationTimeSeconds() {
        return simulationTimeSeconds;
    }

    public void setSimulationTimeSeconds(long simulationTimeSeconds) {
        this.simulationTimeSeconds = simulationTimeSeconds;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
