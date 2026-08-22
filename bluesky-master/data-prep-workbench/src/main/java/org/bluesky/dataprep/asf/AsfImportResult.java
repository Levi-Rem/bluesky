package org.bluesky.dataprep.asf;

import java.util.ArrayList;
import java.util.List;

public class AsfImportResult {
    private int navigationPointCount;
    private int airwayCount;
    private int airwaySegmentCount;
    private int codedRouteCount;
    private int sidCount;
    private int starCount;
    private int duplicateDefinitionCount;
    private List<String> duplicateDefinitions = new ArrayList<>();

    public int getNavigationPointCount() { return navigationPointCount; }
    public void setNavigationPointCount(int value) { this.navigationPointCount = value; }
    public int getAirwayCount() { return airwayCount; }
    public void setAirwayCount(int value) { this.airwayCount = value; }
    public int getAirwaySegmentCount() { return airwaySegmentCount; }
    public void setAirwaySegmentCount(int value) { this.airwaySegmentCount = value; }
    public int getCodedRouteCount() { return codedRouteCount; }
    public void setCodedRouteCount(int value) { this.codedRouteCount = value; }
    public int getSidCount() { return sidCount; }
    public void setSidCount(int value) { this.sidCount = value; }
    public int getStarCount() { return starCount; }
    public void setStarCount(int value) { this.starCount = value; }
    public int getDuplicateDefinitionCount() { return duplicateDefinitionCount; }
    public void setDuplicateDefinitionCount(int value) { this.duplicateDefinitionCount = value; }
    public List<String> getDuplicateDefinitions() { return duplicateDefinitions; }
    public void setDuplicateDefinitions(List<String> value) { this.duplicateDefinitions = value; }
}
