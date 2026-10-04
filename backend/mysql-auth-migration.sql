USE auction;

SET @role_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user_account'
      AND column_name = 'role'
);
SET @role_sql = IF(
    @role_column_exists = 0,
    'ALTER TABLE user_account ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT ''USER''',
    'SELECT 1'
);
PREPARE role_stmt FROM @role_sql;
EXECUTE role_stmt;
DEALLOCATE PREPARE role_stmt;

SET @anchor_user_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'auction_room'
      AND column_name = 'anchor_user_id'
);
SET @anchor_user_sql = IF(
    @anchor_user_column_exists = 0,
    'ALTER TABLE auction_room ADD COLUMN anchor_user_id VARCHAR(32) NULL',
    'SELECT 1'
);
PREPARE anchor_user_stmt FROM @anchor_user_sql;
EXECUTE anchor_user_stmt;
DEALLOCATE PREPARE anchor_user_stmt;

UPDATE user_account
SET role = 'ADMIN'
WHERE account = 'admin'
  AND role <> 'ADMIN';
