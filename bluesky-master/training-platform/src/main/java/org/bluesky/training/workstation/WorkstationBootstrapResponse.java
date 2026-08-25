package org.bluesky.training.workstation;

import org.bluesky.training.adapter.EngineHealth;
import org.bluesky.training.instruction.InstructionResponse;
import org.bluesky.training.display.DisplaySettingsView;
import org.bluesky.training.mapdata.ReferenceDataState;

import java.util.List;

public final class WorkstationBootstrapResponse {
    private final TerminalView terminal;
    private final ExerciseGroupView exerciseGroup;
    private final EngineHealth engine;
    private final ReferenceDataState referenceData;
    private final List<?> aircraft;
    private final List<InstructionResponse> instructions;
    private final UiParametersView uiParameters;
    private final DisplaySettingsView uiParameterDefaults;

    public WorkstationBootstrapResponse(TerminalView terminal,
                                        ExerciseGroupView exerciseGroup,
                                        EngineHealth engine,
                                        ReferenceDataState referenceData,
                                        UiParametersView uiParameters,
                                        DisplaySettingsView uiParameterDefaults,
                                        List<?> aircraft,
                                        List<InstructionResponse> instructions) {
        this.terminal = terminal;
        this.exerciseGroup = exerciseGroup;
        this.engine = engine;
        this.referenceData = referenceData;
        this.aircraft = aircraft;
        this.instructions = instructions;
        this.uiParameters = uiParameters;
        this.uiParameterDefaults = uiParameterDefaults;
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

    public ReferenceDataState getReferenceData() { return referenceData; }

    public List<?> getAircraft() {
        return aircraft;
    }

    public List<InstructionResponse> getInstructions() {
        return instructions;
    }

    public UiParametersView getUiParameters() {
        return uiParameters;
    }

    public DisplaySettingsView getUiParameterDefaults() {
        return uiParameterDefaults;
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
        private final String mapWaypointColor;
        private final String mapAirwayColor;
        private final String mapSectorColor;
        private final String mapSectorFillColor;
        private final String mapWeatherColor;
        private final String mapWeatherFillColor;

        public UiParametersView(String theme, DisplaySettingsView settings) {
            this.theme = theme;
            this.trackColor = settings.getTrackColor();
            this.selectedTrackColor = settings.getSelectedTrackColor();
            this.mapWaypointColor = settings.getMapWaypointColor();
            this.mapAirwayColor = settings.getMapAirwayColor();
            this.mapSectorColor = settings.getMapSectorColor();
            this.mapSectorFillColor = settings.getMapSectorFillColor();
            this.mapWeatherColor = settings.getMapWeatherColor();
            this.mapWeatherFillColor = settings.getMapWeatherFillColor();
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

        public String getMapWaypointColor() { return mapWaypointColor; }
        public String getMapAirwayColor() { return mapAirwayColor; }
        public String getMapSectorColor() { return mapSectorColor; }
        public String getMapSectorFillColor() { return mapSectorFillColor; }
        public String getMapWeatherColor() { return mapWeatherColor; }
        public String getMapWeatherFillColor() { return mapWeatherFillColor; }
    }
}
