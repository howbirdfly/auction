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
    ends_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS auction_bid_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    bid_time TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auction_bid_room_time
    ON auction_bid_record (room_id, bid_time);

CREATE TABLE IF NOT EXISTS user_account (
    user_id VARCHAR(32) PRIMARY KEY,
    account VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(64) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    avatar_url VARCHAR(512),
    bio VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
