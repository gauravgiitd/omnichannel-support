ALTER TABLE tickets ADD COLUMN claim_id VARCHAR(64);
ALTER TABLE tickets ADD COLUMN policy_id VARCHAR(64);

CREATE INDEX idx_tickets_claim ON tickets (claim_id);
CREATE INDEX idx_tickets_policy ON tickets (policy_id);
