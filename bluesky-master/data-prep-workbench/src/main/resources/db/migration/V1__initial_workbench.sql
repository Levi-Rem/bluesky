CREATE TABLE workbench_state (
    id INT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT INTO workbench_state (id, revision) VALUES (1, 0);
