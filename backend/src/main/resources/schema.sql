CREATE TABLE IF NOT EXISTS auction_room (
    room_id VARCHAR(32) PRIMARY KEY,
    item_title VARCHAR(128) NOT NULL,
    anchor_name VARCHAR(64) NOT NULL,
    image_url VARCHAR(512),
    start_price DECIMAL(12, 2) NOT NULL,
    step_price DECIMAL(12, 2) NOT NULL,
    current_price DECIMAL(12, 2) NOT NULL,
    leader_user_id VARCHAR(64),
    leader_nickname VARCHAR(64),
    registration_required BOOLEAN NOT NULL DEFAULT FALSE,
    deposit_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Backfill columns for existing MySQL/H2 tables created before qualification support.
ALTER TABLE auction_room
    ADD COLUMN IF NOT EXISTS registration_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auction_room
    ADD COLUMN IF NOT EXISTS deposit_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00;

ALTER TABLE auction_room
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS auction_bid_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64),
    request_id VARCHAR(64),
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    bid_version BIGINT NOT NULL DEFAULT 0,
    bid_time TIMESTAMP NOT NULL
);

ALTER TABLE auction_bid_record
    ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);

ALTER TABLE auction_bid_record
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);

ALTER TABLE auction_bid_record
    ADD COLUMN IF NOT EXISTS bid_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_auction_bid_room_time
    ON auction_bid_record (room_id, bid_time);

CREATE UNIQUE INDEX IF NOT EXISTS uk_auction_bid_event_id
    ON auction_bid_record (event_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_auction_bid_request_id
    ON auction_bid_record (request_id);

CREATE TABLE IF NOT EXISTS auction_bid_persistence_log (
    event_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(64),
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255),
    payload_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    persisted_at TIMESTAMP
);

ALTER TABLE auction_bid_persistence_log
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);

ALTER TABLE auction_bid_persistence_log
    ADD COLUMN IF NOT EXISTS payload_json TEXT;

CREATE TABLE IF NOT EXISTS auction_bid_request (
    request_id VARCHAR(64) PRIMARY KEY,
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    bid_version BIGINT,
    error_message VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auction_bid_request_room_user
    ON auction_bid_request (room_id, user_id);

CREATE TABLE IF NOT EXISTS auction_room_registration (
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    deposit_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (room_id, user_id)
);

CREATE TABLE IF NOT EXISTS auction_settlement_log (
    room_id VARCHAR(32) PRIMARY KEY,
    winner_user_id VARCHAR(64),
    final_price DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(16) NOT NULL,
    winner_funds_settled BOOLEAN NOT NULL DEFAULT FALSE,
    deposits_released BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    settled_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_account (
    user_id VARCHAR(32) PRIMARY KEY,
    account VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(64) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    avatar_url VARCHAR(512),
    bio VARCHAR(255),
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00;

ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS frozen_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00;

CREATE TABLE IF NOT EXISTS wallet_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_key VARCHAR(128),
    user_id VARCHAR(32) NOT NULL,
    account VARCHAR(32) NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    available_delta DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_delta DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    balance_after DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_after DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    reference_type VARCHAR(32),
    reference_id VARCHAR(64),
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_transaction_business_key
    ON wallet_transaction (business_key);

CREATE INDEX IF NOT EXISTS idx_wallet_transaction_user_created
    ON wallet_transaction (user_id, created_at);
