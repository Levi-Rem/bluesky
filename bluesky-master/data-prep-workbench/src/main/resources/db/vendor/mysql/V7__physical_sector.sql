-- 物理扇区：一个同名扇区可对应多个独立的水平区域和高度范围。
CREATE TABLE physical_sector (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    sector_type VARCHAR(16) NOT NULL,
    composition_mode VARCHAR(16) NOT NULL,
    upper_limit VARCHAR(16) NOT NULL,
    lower_limit VARCHAR(16) NOT NULL,
    source_subtype VARCHAR(16),
    source_flag VARCHAR(8),
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_physical_sector_name ON physical_sector (name);

CREATE TABLE physical_sector_point (
    id VARCHAR(36) PRIMARY KEY,
    physical_sector_id VARCHAR(36) NOT NULL,
    order_no INT NOT NULL,
    nav_point_id VARCHAR(36),
    point_name VARCHAR(128),
    coordinate_text VARCHAR(32) NOT NULL,
    longitude DECIMAL(9, 6) NOT NULL,
    latitude DECIMAL(9, 6) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_physical_sector_point FOREIGN KEY (physical_sector_id) REFERENCES physical_sector (id)
);
CREATE INDEX idx_physical_sector_point_sector ON physical_sector_point (physical_sector_id, order_no);
