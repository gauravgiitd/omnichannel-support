ALTER TABLE IF EXISTS tickets RENAME TO tasks;

ALTER TABLE IF EXISTS tasks
    RENAME COLUMN ticket_number TO task_number;

ALTER TABLE IF EXISTS messages
    RENAME COLUMN ticket_id TO task_id;

ALTER TABLE IF EXISTS ticket_documents RENAME TO task_documents;

ALTER TABLE IF EXISTS task_documents
    RENAME COLUMN ticket_id TO task_id;

ALTER TABLE IF EXISTS ticket_merge_map RENAME TO task_merge_map;

ALTER TABLE IF EXISTS task_merge_map
    RENAME COLUMN primary_ticket_id TO primary_task_id;

ALTER TABLE IF EXISTS task_merge_map
    RENAME COLUMN merged_ticket_id TO merged_task_id;

ALTER TABLE IF EXISTS customer_conversation_contexts
    RENAME COLUMN active_ticket_number TO active_task_number;

ALTER INDEX IF EXISTS idx_tickets_customer_status RENAME TO idx_tasks_customer_status;
ALTER INDEX IF EXISTS idx_tickets_ticket_number RENAME TO idx_tasks_task_number;
ALTER INDEX IF EXISTS idx_messages_ticket_created RENAME TO idx_messages_task_created;
ALTER INDEX IF EXISTS idx_ticket_documents_ticket RENAME TO idx_task_documents_task;
ALTER INDEX IF EXISTS idx_ticket_documents_customer RENAME TO idx_task_documents_customer;
ALTER INDEX IF EXISTS idx_ticket_documents_claim RENAME TO idx_task_documents_claim;
ALTER INDEX IF EXISTS idx_ticket_documents_policy RENAME TO idx_task_documents_policy;
ALTER INDEX IF EXISTS idx_tickets_claim RENAME TO idx_tasks_claim;
ALTER INDEX IF EXISTS idx_tickets_policy RENAME TO idx_tasks_policy;

ALTER TABLE IF EXISTS messages
    DROP CONSTRAINT IF EXISTS fk_messages_ticket,
    ADD CONSTRAINT fk_messages_task FOREIGN KEY (task_id) REFERENCES tasks (id);

ALTER TABLE IF EXISTS task_documents
    DROP CONSTRAINT IF EXISTS fk_ticket_documents_ticket,
    ADD CONSTRAINT fk_task_documents_task FOREIGN KEY (task_id) REFERENCES tasks (id);

ALTER TABLE IF EXISTS task_merge_map
    DROP CONSTRAINT IF EXISTS fk_merge_primary,
    DROP CONSTRAINT IF EXISTS fk_merge_merged,
    DROP CONSTRAINT IF EXISTS uq_merge_pair,
    ADD CONSTRAINT uq_task_merge_pair UNIQUE (merged_task_id),
    ADD CONSTRAINT fk_task_merge_primary FOREIGN KEY (primary_task_id) REFERENCES tasks (id),
    ADD CONSTRAINT fk_task_merge_merged FOREIGN KEY (merged_task_id) REFERENCES tasks (id);
