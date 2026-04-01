ALTER TABLE messages
    ADD COLUMN intent_type VARCHAR(32) NULL;

ALTER TABLE tasks
    ADD COLUMN task_type VARCHAR(32) NULL,
    ADD COLUMN execution_tier VARCHAR(32) NULL;

CREATE TABLE message_jtbd_links (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    customer_jtbd_id BIGINT NOT NULL REFERENCES customer_jtbds(id) ON DELETE CASCADE,
    linkage_type VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE assignments (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id BIGINT NULL REFERENCES messages(id) ON DELETE SET NULL,
    assigned_group VARCHAR(128) NOT NULL,
    assigned_agent VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE handling_sessions (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    assigned_group VARCHAR(128) NOT NULL,
    assigned_agent VARCHAR(128) NULL,
    start_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    end_at TIMESTAMPTZ NULL
);

CREATE TABLE document_links (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES task_documents(id) ON DELETE CASCADE,
    conversation_id BIGINT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id BIGINT NULL REFERENCES messages(id) ON DELETE CASCADE,
    customer_jtbd_id BIGINT NULL REFERENCES customer_jtbds(id) ON DELETE CASCADE,
    task_id BIGINT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
