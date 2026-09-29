-- Schema for H2 in-memory test database
CREATE TABLE IF NOT EXISTS bookings (
    id       VARCHAR(50)  PRIMARY KEY,
    guest    VARCHAR(255) NOT NULL,
    room     VARCHAR(50)  NOT NULL,
    checkin  VARCHAR(20)  NOT NULL,
    checkout VARCHAR(20)  NOT NULL
);
