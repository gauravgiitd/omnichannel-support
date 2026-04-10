ALTER TABLE whatsapp_calls
    ADD COLUMN session_sdp_type VARCHAR(32),
    ADD COLUMN session_sdp TEXT,
    ADD COLUMN phone_number_id VARCHAR(128),
    ADD COLUMN display_phone_number VARCHAR(64),
    ADD COLUMN start_time TIMESTAMP,
    ADD COLUMN end_time TIMESTAMP,
    ADD COLUMN duration_seconds INTEGER;
