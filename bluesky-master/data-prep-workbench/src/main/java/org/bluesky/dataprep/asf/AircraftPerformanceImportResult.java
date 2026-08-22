package org.bluesky.dataprep.asf;

import java.util.ArrayList;
import java.util.List;

public class AircraftPerformanceImportResult {
    private int performanceGroupCount;
    private int aircraftTypeCount;
    private int performanceRowCount;
    private List<String> warnings = new ArrayList<>();

    public int getPerformanceGroupCount() { return performanceGroupCount; }
    public void setPerformanceGroupCount(int value) { performanceGroupCount = value; }
    public int getAircraftTypeCount() { return aircraftTypeCount; }
    public void setAircraftTypeCount(int value) { aircraftTypeCount = value; }
    public int getPerformanceRowCount() { return performanceRowCount; }
    public void setPerformanceRowCount(int value) { performanceRowCount = value; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> value) { warnings = value; }
}
