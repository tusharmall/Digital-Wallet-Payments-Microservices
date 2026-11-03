CREATE TABLE app_users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(190) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_users_email UNIQUE (email)
) ENGINE=InnoDB;

CREATE TABLE wallets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallets_user_id UNIQUE (user_id),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES app_users(id),
    CONSTRAINT chk_wallet_balance_nonnegative CHECK (balance >= 0)
) ENGINE=InnoDB;

CREATE TABLE wallet_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type VARCHAR(24) NOT NULL,
    source_wallet_id BIGINT NULL,
    target_wallet_id BIGINT NULL,
    source_user_id BIGINT NULL,
    target_user_id BIGINT NULL,
    requested_by_user_id BIGINT NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tx_requester_idempotency UNIQUE (requested_by_user_id, idempotency_key),
    CONSTRAINT fk_tx_source_wallet FOREIGN KEY (source_wallet_id) REFERENCES wallets(id),
    CONSTRAINT fk_tx_target_wallet FOREIGN KEY (target_wallet_id) REFERENCES wallets(id),
    CONSTRAINT fk_tx_requester FOREIGN KEY (requested_by_user_id) REFERENCES app_users(id),
    CONSTRAINT chk_transaction_amount_positive CHECK (amount > 0),
    INDEX idx_tx_from_created (source_wallet_id, created_at),
    INDEX idx_tx_to_created (target_wallet_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT NOT NULL,
    wallet_id BIGINT NULL,
    account_code VARCHAR(32) NOT NULL,
    entry_type VARCHAR(6) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES wallet_transactions(id),
    CONSTRAINT fk_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0),
    INDEX idx_ledger_transaction_id (transaction_id)
) ENGINE=InnoDB;

CREATE TABLE outbox_events (
    id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_pending (published_at, created_at)
) ENGINE=InnoDB;
