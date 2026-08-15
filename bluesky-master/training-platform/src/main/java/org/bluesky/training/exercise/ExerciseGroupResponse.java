package org.bluesky.training.exercise;

import org.bluesky.training.persistence.ExerciseGroupRow;

public final class ExerciseGroupResponse {
    private final String id;
    private final String name;
    private final String state;
    private final long simulationTimeSeconds;

    public ExerciseGroupResponse(ExerciseGroupRow row) {
        this.id = row.getId();
        this.name = row.getName();
        this.state = row.getState();
        this.simulationTimeSeconds = row.getSimulationTimeSeconds();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getState() {
        return state;
    }

    public long getSimulationTimeSeconds() {
        return simulationTimeSeconds;
    }
}
