-- PostgreSQL 16 schema initialisation for ResortsLite
-- Replaces H2 in-memory schema. Uses snake_case naming convention per PostgreSQL best practices.

CREATE TABLE IF NOT EXISTS bookings (
    id        VARCHAR(20)  PRIMARY KEY,
    guest     VARCHAR(255) NOT NULL,
    room      VARCHAR(50)  NOT NULL,
    checkin   DATE         NOT NULL,
    checkout  DATE         NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
