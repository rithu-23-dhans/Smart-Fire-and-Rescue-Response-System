CREATE DATABASE IF NOT EXISTS fire_rescue
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE fire_rescue;

CREATE TABLE IF NOT EXISTS incidents (
  id VARCHAR(20) PRIMARY KEY,
  location VARCHAR(80) NOT NULL,
  type VARCHAR(40) NOT NULL,
  intensity INT NOT NULL,
  population_affected INT NOT NULL,
  critical BOOLEAN NOT NULL,
  arrival_order INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  assigned_unit VARCHAR(20) NOT NULL DEFAULT 'None'
);

CREATE TABLE IF NOT EXISTS response_units (
  id VARCHAR(20) PRIMARY KEY,
  unit_type VARCHAR(30) NOT NULL,
  station VARCHAR(80) NOT NULL,
  location VARCHAR(80) NOT NULL,
  available BOOLEAN NOT NULL,
  status VARCHAR(20) NOT NULL,
  incident_id VARCHAR(20) NOT NULL DEFAULT 'None',
  capabilities VARCHAR(255) NOT NULL,
  route_text TEXT,
  route_index INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS roads (
  from_location VARCHAR(80) NOT NULL,
  to_location VARCHAR(80) NOT NULL,
  travel_time INT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (from_location, to_location)
);

CREATE TABLE IF NOT EXISTS dispatch_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id VARCHAR(20) NOT NULL,
  unit_id VARCHAR(20) NOT NULL,
  route_text TEXT NOT NULL,
  travel_time INT NOT NULL,
  dispatched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
