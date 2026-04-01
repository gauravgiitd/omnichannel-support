CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    customer_id VARCHAR(64) NOT NULL UNIQUE,
    primary_channel VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE tasks
    ADD COLUMN conversation_id BIGINT NULL;

ALTER TABLE messages
    ADD COLUMN conversation_id BIGINT NULL,
    ADD COLUMN customer_jtbd_id BIGINT NULL;

ALTER TABLE task_documents
    ADD COLUMN conversation_id BIGINT NULL,
    ADD COLUMN customer_jtbd_id BIGINT NULL;

ALTER TABLE tasks
    ADD CONSTRAINT fk_tasks_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations(id);

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    ADD CONSTRAINT fk_messages_customer_jtbd
        FOREIGN KEY (customer_jtbd_id) REFERENCES customer_jtbds(id);

ALTER TABLE task_documents
    ADD CONSTRAINT fk_task_documents_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    ADD CONSTRAINT fk_task_documents_customer_jtbd
        FOREIGN KEY (customer_jtbd_id) REFERENCES customer_jtbds(id);
