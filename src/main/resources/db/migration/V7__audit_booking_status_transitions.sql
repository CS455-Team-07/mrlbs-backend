CREATE TABLE booking_status_events (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_email VARCHAR(320) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note VARCHAR(1000)
);

CREATE INDEX idx_booking_status_events_booking_id_occurred_at
    ON booking_status_events (booking_id, occurred_at);
