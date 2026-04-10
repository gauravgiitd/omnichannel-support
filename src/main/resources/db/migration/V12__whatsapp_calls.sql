CREATE TABLE whatsapp_calls (
    id BIGSERIAL PRIMARY KEY,
    call_id VARCHAR(128) NOT NULL UNIQUE,
    customer_id VARCHAR(64),
    phone_number VARCHAR(64),
    from_phone VARCHAR(64),
    to_phone VARCHAR(64),
    status VARCHAR(64),
    direction VARCHAR(64),
    event VARCHAR(64),
    permission_requested_by VARCHAR(256),
    permission_requested_at TIMESTAMP,
    external_message_id VARCHAR(256),
    raw_payload_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_whatsapp_calls_customer_id ON whatsapp_calls(customer_id);
CREATE INDEX idx_whatsapp_calls_phone_number ON whatsapp_calls(phone_number);
