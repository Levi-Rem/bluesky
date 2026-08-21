package org.bluesky.dataprep.map;

import java.util.Map;

/** 地图批量保存操作（对应用户数据结构 MapEditOperation 的一期子集）。 */
public class MapFeatureOperation {

    /** CREATE / UPDATE_GEOMETRY / UPDATE_PROPERTIES / DELETE */
    private String operationType;
    /** nav-point / airspace */
    private String entityType;
    private String entityId;
    private int revision;
    /** GeoJSON 对象文本（UPDATE_GEOMETRY） */
    private String geometry;
    /** 属性补丁（UPDATE_PROPERTIES）：code/name 等 */
    private Map<String, Object> properties;

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getGeometry() {
        return geometry;
    }

    public void setGeometry(String geometry) {
        this.geometry = geometry;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
}
