CREATE TABLE secret_store (
    ns VARCHAR(128) NOT NULL,
    secret_key VARCHAR(255) NOT NULL,
    cipher_text CLOB NOT NULL,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (ns, secret_key)
);
