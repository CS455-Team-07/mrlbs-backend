CREATE TABLE credit_accounts (
    member_email VARCHAR(320) PRIMARY KEY,
    balance NUMERIC(12, 0) NOT NULL CHECK (balance >= 0),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    owner_email VARCHAR(320) NOT NULL,
    resource_id UUID NOT NULL REFERENCES resources (id),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    credit_cost NUMERIC(12, 0) NOT NULL CHECK (credit_cost >= 0),
    CONSTRAINT booking_time_window_valid CHECK (end_time > start_time)
);

CREATE INDEX bookings_owner_start_idx ON bookings (owner_email, start_time DESC);
CREATE INDEX bookings_resource_time_idx ON bookings (resource_id, start_time, end_time);
