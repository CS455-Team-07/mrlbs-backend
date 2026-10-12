CREATE TABLE booking_rules (
    id BIGSERIAL PRIMARY KEY,
    booking_policy VARCHAR(64) NOT NULL DEFAULT 'OPEN',
    min_duration_minutes INTEGER NOT NULL DEFAULT 30,
    max_duration_minutes INTEGER NOT NULL DEFAULT 180,
    buffer_time_minutes INTEGER NOT NULL DEFAULT 15,
    max_bookings_per_member INTEGER NOT NULL DEFAULT 3
);

ALTER TABLE resources
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN base_rate NUMERIC(10, 2) NOT NULL DEFAULT 1.00,
    ADD COLUMN booking_rule_id BIGINT REFERENCES booking_rules (id);
