ALTER TABLE tasks ADD COLUMN claim_id VARCHAR(64);
ALTER TABLE tasks ADD COLUMN policy_id VARCHAR(64);

CREATE INDEX idx_tasks_claim ON tasks (claim_id);
CREATE INDEX idx_tasks_policy ON tasks (policy_id);
