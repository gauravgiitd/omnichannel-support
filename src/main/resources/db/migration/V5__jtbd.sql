CREATE TABLE jtbd_types (
    id BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE jtbd_type_stages (
    id BIGSERIAL PRIMARY KEY,
    jtbd_type_id BIGINT NOT NULL REFERENCES jtbd_types(id) ON DELETE CASCADE,
    stage_key VARCHAR(128) NOT NULL,
    stage_name VARCHAR(256) NOT NULL,
    stage_order INT NOT NULL,
    terminal_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_jtbd_stage_key UNIQUE (jtbd_type_id, stage_key),
    CONSTRAINT uq_jtbd_stage_order UNIQUE (jtbd_type_id, stage_order)
);

CREATE TABLE customer_jtbds (
    id BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    customer_id VARCHAR(64) NOT NULL,
    jtbd_type_id BIGINT NOT NULL REFERENCES jtbd_types(id) ON DELETE RESTRICT,
    current_stage_id BIGINT NOT NULL REFERENCES jtbd_type_stages(id) ON DELETE RESTRICT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE tasks
    ADD COLUMN customer_jtbd_id BIGINT NULL REFERENCES customer_jtbds(id) ON DELETE SET NULL;

CREATE TABLE customer_conversation_contexts (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    active_task_number VARCHAR(32),
    active_customer_jtbd_public_id VARCHAR(36),
    pending_selection_type VARCHAR(32),
    pending_options_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_customer_channel_context UNIQUE (customer_id, channel)
);
