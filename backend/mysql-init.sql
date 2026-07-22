CREATE DATABASE IF NOT EXISTS auction
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE auction;

CREATE TABLE IF NOT EXISTS auction_room (
    room_id VARCHAR(32) PRIMARY KEY,
    item_title VARCHAR(128) NOT NULL,
    anchor_name VARCHAR(64) NOT NULL,
    image_url VARCHAR(512) NULL,
    start_price DECIMAL(12, 2) NOT NULL,
    step_price DECIMAL(12, 2) NOT NULL,
    current_price DECIMAL(12, 2) NOT NULL,
    leader_user_id VARCHAR(64) NULL,
    leader_nickname VARCHAR(64) NULL,
    registration_required BOOLEAN NOT NULL DEFAULT FALSE,
    deposit_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS auction_bid_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NULL,
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    bid_version BIGINT NOT NULL DEFAULT 0,
    bid_time TIMESTAMP NOT NULL,
    KEY idx_auction_bid_room_time (room_id, bid_time),
    UNIQUE KEY uk_auction_bid_event_id (event_id)
);

CREATE TABLE IF NOT EXISTS auction_bid_persistence_log (
    event_id VARCHAR(64) PRIMARY KEY,
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    persisted_at TIMESTAMP NULL
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

CREATE TABLE IF NOT EXISTS user_account (
    user_id VARCHAR(32) PRIMARY KEY,
    account VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(64) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    avatar_url VARCHAR(512) NULL,
    bio VARCHAR(255) NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    frozen_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS wallet_reconcile_issue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
