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
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
