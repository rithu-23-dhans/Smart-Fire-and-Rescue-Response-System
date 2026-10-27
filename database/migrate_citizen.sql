-- Safe migration: add citizen tables and link incidents to citizens
-- Run against fire_rescue database

CREATE TABLE IF NOT EXISTS citizen_users (
  id INT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(60) UNIQUE NOT NULL,
  email VARCHAR(120) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  salt VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS citizen_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  citizen_id INT NOT NULL,
  token VARCHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_token (token),
  FOREIGN KEY (citizen_id) REFERENCES citizen_users(id) ON DELETE CASCADE
);

-- Add reported_by only if it does not exist
SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'fire_rescue'
    AND TABLE_NAME   = 'incidents'
    AND COLUMN_NAME  = 'reported_by'
);

SET @sql = IF(@col_exists = 0,
  'ALTER TABLE incidents ADD COLUMN reported_by INT DEFAULT NULL',
  'SELECT 1 -- column already exists'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
