package org.bluesky.training.persistence;

import java.sql.Timestamp;

/** engine_instance 行（V9）。 */
public class EngineInstanceRow {

    private String id;
    private String exerciseGroupId;
    private String controlEndpoint;
    private String stateEndpoint;
    private String protocolVersion;
    private String processIdentifier;
    private String state;
    private long lastOutboundSequence;
    private long lastInboundSequence;
    private String referenceSnapshotChecksum;
    private Timestamp lastHeartbeatAt;
    private long revision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExerciseGroupId() {
        return exerciseGroupId;
    }

    public void setExerciseGroupId(String exerciseGroupId) {
        this.exerciseGroupId = exerciseGroupId;
    }

    public String getControlEndpoint() {
        return controlEndpoint;
    }

    public void setControlEndpoint(String controlEndpoint) {
        this.controlEndpoint = controlEndpoint;
    }

    public String getStateEndpoint() {
        return stateEndpoint;
    }

    public void setStateEndpoint(String stateEndpoint) {
        this.stateEndpoint = stateEndpoint;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getProcessIdentifier() {
        return processIdentifier;
    }

    public void setProcessIdentifier(String processIdentifier) {
        this.processIdentifier = processIdentifier;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public long getLastOutboundSequence() {
        return lastOutboundSequence;
    }

    public void setLastOutboundSequence(long lastOutboundSequence) {
        this.lastOutboundSequence = lastOutboundSequence;
    }

    public long getLastInboundSequence() {
        return lastInboundSequence;
    }

    public void setLastInboundSequence(long lastInboundSequence) {
        this.lastInboundSequence = lastInboundSequence;
    }

    public String getReferenceSnapshotChecksum() {
        return referenceSnapshotChecksum;
    }

    public void setReferenceSnapshotChecksum(String referenceSnapshotChecksum) {
        this.referenceSnapshotChecksum = referenceSnapshotChecksum;
    }

    public Timestamp getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Timestamp lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
