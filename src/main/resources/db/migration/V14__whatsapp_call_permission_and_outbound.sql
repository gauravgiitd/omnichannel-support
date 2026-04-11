ALTER TABLE whatsapp_calls
    ADD COLUMN permission_status VARCHAR(32),
    ADD COLUMN permission_status_updated_at TIMESTAMP,
    ADD COLUMN permission_expires_at TIMESTAMP,
    ADD COLUMN permission_source VARCHAR(64),
    ADD COLUMN initiated_by VARCHAR(256),
    ADD COLUMN initiated_at TIMESTAMP,
    ADD COLUMN biz_opaque_callback_data VARCHAR(256);
