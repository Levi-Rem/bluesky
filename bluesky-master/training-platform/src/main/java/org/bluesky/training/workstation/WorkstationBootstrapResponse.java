package org.bluesky.training.workstation;

import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.instruction.InstructionResponse;

import java.util.List;

public final class WorkstationBootstrapResponse {
    private final TerminalView terminal;
    private final ExerciseGroupView exerciseGroup;
    private final EngineHealth engine;
    private final List<?> aircraft;
    private final List<InstructionResponse> instructions;
    private final UiParametersView uiParameters;

    public WorkstationBootstrapResponse(TerminalView terminal,
                                        ExerciseGroupView exerciseGroup,
                                        EngineHealth engine,
                                        UiParametersView uiParameters,
                                        List<?> aircraft,
                                        List<InstructionResponse> instructions) {
        this.terminal = terminal;
        this.exerciseGroup = exerciseGroup;
        this.engine = engine;
        this.aircraft = aircraft;
        this.instructions = instructions;
        this.uiParameters = uiParameters;
    }

    public TerminalView getTerminal() {
        return terminal;
    }

    public ExerciseGroupView getExerciseGroup() {
        return exerciseGroup;
    }

    public EngineHealth getEngine() {
        return engine;
    }

    public List<?> getAircraft() {
        return aircraft;
    }

    public List<InstructionResponse> getInstructions() {
        return instructions;
    }

    public UiParametersView getUiParameters() {
        return uiParameters;
    }

    public static final class TerminalView {
        private final String id;
        private final String name;

        public TerminalView(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public static final class ExerciseGroupView {
        private final String id;
        private final String name;
        private final String state;
        private final long simulationTimeSeconds;

        public ExerciseGroupView(String id, String name, String state, long simulationTimeSeconds) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.simulationTimeSeconds = simulationTimeSeconds;
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

    public static final class UiParametersView {
        private final String theme;
        private final String trackColor;
        private final String selectedTrackColor;

        public UiParametersView(String theme, String trackColor, String selectedTrackColor) {
            this.theme = theme;
            this.trackColor = trackColor;
            this.selectedTrackColor = selectedTrackColor;
        }

        public String getTheme() {
            return theme;
        }

        public String getTrackColor() {
            return trackColor;
        }

        public String getSelectedTrackColor() {
            return selectedTrackColor;
        }
    }
}
