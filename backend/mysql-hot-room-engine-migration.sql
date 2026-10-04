USE auction;

SET @engine_mode_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'auction_room'
      AND column_name = 'engine_mode'
);
SET @engine_mode_sql = IF(
    @engine_mode_column_exists = 0,
    'ALTER TABLE auction_room ADD COLUMN engine_mode VARCHAR(16) NOT NULL DEFAULT ''MYSQL''',
    'SELECT 1'
);
PREPARE engine_mode_stmt FROM @engine_mode_sql;
EXECUTE engine_mode_stmt;
DEALLOCATE PREPARE engine_mode_stmt;
