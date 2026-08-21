package org.bluesky.dataprep.radar;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AsterixChannelRow {

    private String id;
    private String code;
    private String name;
    private String category;
    private String edition;
    private Integer periodMs;
    private String transmissionMode;
    private String destinationIp;
    private Integer destinationPort;
    private String networkInterface;
    private Integer ttl;
    private Integer maximumDatagramBytes = 1400;
    private Boolean channelEnabled = Boolean.TRUE;
    private Integer configRevision = 0;
    private String status = "ENABLED";
    private String sourceType = "MANUAL";
    private String sourceReference;
    private int revision;
    private LocalDateTime createdAt;
    private String createdBy = "local";
    private LocalDateTime updatedAt;
    private String updatedBy = "local";
    /** 保存时以站点 id 列表提交绑定（replace-all）；详情返回绑定站点 id。 */
    private List<String> boundSiteIds = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getEdition() {
        return edition;
    }

    public void setEdition(String edition) {
        this.edition = edition;
    }

    public Integer getPeriodMs() {
        return periodMs;
    }

    public void setPeriodMs(Integer periodMs) {
        this.periodMs = periodMs;
    }

    public String getTransmissionMode() {
        return transmissionMode;
    }

    public void setTransmissionMode(String transmissionMode) {
        this.transmissionMode = transmissionMode;
    }

    public String getDestinationIp() {
        return destinationIp;
    }

    public void setDestinationIp(String destinationIp) {
        this.destinationIp = destinationIp;
    }

    public Integer getDestinationPort() {
        return destinationPort;
    }

    public void setDestinationPort(Integer destinationPort) {
        this.destinationPort = destinationPort;
    }

    public String getNetworkInterface() {
        return networkInterface;
    }

    public void setNetworkInterface(String networkInterface) {
        this.networkInterface = networkInterface;
    }

    public Integer getTtl() {
        return ttl;
    }

    public void setTtl(Integer ttl) {
        this.ttl = ttl;
    }

    public Integer getMaximumDatagramBytes() {
        return maximumDatagramBytes;
    }

    public void setMaximumDatagramBytes(Integer maximumDatagramBytes) {
        this.maximumDatagramBytes = maximumDatagramBytes;
    }

    public Boolean getChannelEnabled() {
        return channelEnabled;
    }

    public void setChannelEnabled(Boolean channelEnabled) {
        this.channelEnabled = channelEnabled;
    }

    public Integer getConfigRevision() {
        return configRevision;
    }

    public void setConfigRevision(Integer configRevision) {
        this.configRevision = configRevision;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public List<String> getBoundSiteIds() {
        return boundSiteIds;
    }

    public void setBoundSiteIds(List<String> boundSiteIds) {
        this.boundSiteIds = boundSiteIds;
    }
}
