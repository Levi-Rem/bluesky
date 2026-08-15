package org.bluesky.training.persistence;

public class ExerciseGroupRow {
    private String id;
    private String name;
    private String state;
    private long simulationTimeSeconds;

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

    public long getSimulationTimeSeconds() {
        return simulationTimeSeconds;
    }

    public void setSimulationTimeSeconds(long simulationTimeSeconds) {
        this.simulationTimeSeconds = simulationTimeSeconds;
    }
}
