CREATE TABLE kyc_documents (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    object_key VARCHAR(700) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reviewed_by VARCHAR(190) NULL,
    review_note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_kyc_object_key UNIQUE (object_key),
    INDEX idx_kyc_user_created (user_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE statement_jobs (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    wallet_id BIGINT NOT NULL,
    statement_month VARCHAR(7) NOT NULL,
    cutoff_at DATETIME(6) NOT NULL,
    expected_transaction_count BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    object_key VARCHAR(700) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_statement_user_created (user_id, created_at),
    INDEX idx_statement_wallet_month (wallet_id, statement_month)
) ENGINE=InnoDB;

CREATE TABLE transaction_events (
    event_id VARCHAR(36) NOT NULL,
    transaction_id BIGINT NOT NULL,
    transaction_type VARCHAR(24) NOT NULL,
    from_wallet_id BIGINT NULL,
    to_wallet_id BIGINT NULL,
    from_user_id BIGINT NULL,
    to_user_id BIGINT NULL,
    from_email VARCHAR(190) NULL,
    to_email VARCHAR(190) NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    notification_sent BIT(1) NOT NULL DEFAULT b'0',
    consumed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_event_from_time (from_wallet_id, occurred_at),
    INDEX idx_event_to_time (to_wallet_id, occurred_at),
    INDEX idx_event_transaction (transaction_id)
) ENGINE=InnoDB;
