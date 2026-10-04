CREATE DATABASE IF NOT EXISTS auction_bench
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE auction_bench;

CREATE TABLE IF NOT EXISTS auction_room (
    room_id VARCHAR(32) PRIMARY KEY,
    item_title VARCHAR(128) NOT NULL,
    anchor_name VARCHAR(64) NOT NULL,
    anchor_user_id VARCHAR(32),
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
    version BIGINT NOT NULL DEFAULT 0,
    engine_mode VARCHAR(16) NOT NULL DEFAULT 'MYSQL'
);

CREATE TABLE IF NOT EXISTS auction_bid_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64),
    request_id VARCHAR(64),
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    bid_version BIGINT NOT NULL DEFAULT 0,
    bid_time TIMESTAMP NOT NULL,
    UNIQUE KEY uk_auction_bid_event_id (event_id),
    UNIQUE KEY uk_auction_bid_request_id (request_id),
    KEY idx_auction_bid_room_time (room_id, bid_time)
);

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
    persisted_at TIMESTAMP NULL
);

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
    updated_at TIMESTAMP NOT NULL,
    KEY idx_auction_bid_request_room_user (room_id, user_id)
);

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
    settled_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS user_account (
    user_id VARCHAR(32) PRIMARY KEY,
    account VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(64) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    avatar_url VARCHAR(512),
    bio VARCHAR(255),
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

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
    created_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_wallet_transaction_business_key (business_key),
    KEY idx_wallet_transaction_user_created (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS wallet_reconcile_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    account VARCHAR(32) NOT NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    latest_balance_after DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    latest_frozen_after DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    balance_diff DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_diff DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    transaction_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    KEY idx_wallet_reconcile_issue_user_status (user_id, status),
    KEY idx_wallet_reconcile_issue_created (created_at)
);
