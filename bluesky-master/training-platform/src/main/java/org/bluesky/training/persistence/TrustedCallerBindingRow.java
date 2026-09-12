package org.bluesky.training.persistence;

import java.sql.Timestamp;

/** trusted_caller_binding 行（V8）。 */
public class TrustedCallerBindingRow {

    private String id;
    private String terminalId;
    private String exerciseGroupId;
    private String certificateFingerprintDigest;
    private boolean enabled;
    private Timestamp boundAt;
    private Timestamp lastSeenAt;
    private long revision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(String terminalId) {
        this.terminalId = terminalId;
    }

    public String getExerciseGroupId() {
        return exerciseGroupId;
    }

    public void setExerciseGroupId(String exerciseGroupId) {
        this.exerciseGroupId = exerciseGroupId;
    }

    public String getCertificateFingerprintDigest() {
        return certificateFingerprintDigest;
    }

    public void setCertificateFingerprintDigest(String certificateFingerprintDigest) {
        this.certificateFingerprintDigest = certificateFingerprintDigest;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Timestamp getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Timestamp boundAt) {
        this.boundAt = boundAt;
    }

    public Timestamp getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Timestamp lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
