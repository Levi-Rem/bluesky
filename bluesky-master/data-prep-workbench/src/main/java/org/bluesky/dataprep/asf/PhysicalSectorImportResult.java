package org.bluesky.dataprep.asf;

public class PhysicalSectorImportResult {
    private int sourceSectorCount;
    private int sourceFirCount;
    private int regionCount;
    private int boundaryPointCount;

    public int getSourceSectorCount() { return sourceSectorCount; }
    public void setSourceSectorCount(int sourceSectorCount) { this.sourceSectorCount = sourceSectorCount; }
    public int getSourceFirCount() { return sourceFirCount; }
    public void setSourceFirCount(int sourceFirCount) { this.sourceFirCount = sourceFirCount; }
    public int getRegionCount() { return regionCount; }
    public void setRegionCount(int regionCount) { this.regionCount = regionCount; }
    public int getBoundaryPointCount() { return boundaryPointCount; }
    public void setBoundaryPointCount(int boundaryPointCount) { this.boundaryPointCount = boundaryPointCount; }
}
