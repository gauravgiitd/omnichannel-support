CREATE TABLE admin_settings (
    setting_key VARCHAR(128) PRIMARY KEY,
    setting_value VARCHAR(2048),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
