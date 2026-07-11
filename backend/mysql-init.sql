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
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS auction_bid_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    bid_time TIMESTAMP NOT NULL,
    KEY idx_auction_bid_room_time (room_id, bid_time)
);
