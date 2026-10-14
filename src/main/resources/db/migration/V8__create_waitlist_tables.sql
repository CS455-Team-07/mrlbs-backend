CREATE TABLE IF NOT EXISTS waitlist_entries (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    resource_id UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP,
    booking_id UUID REFERENCES bookings (id) ON DELETE SET NULL,
    credit_cost NUMERIC(12, 0),
    contention_multiplier NUMERIC(8, 2),
    CONSTRAINT waitlist_time_window_valid CHECK (end_time > start_time)
);

CREATE INDEX IF NOT EXISTS idx_waitlist_entries_member ON waitlist_entries (member_id);
CREATE INDEX IF NOT EXISTS idx_waitlist_entries_resource_window ON waitlist_entries (resource_id, start_time, end_time, status);
CREATE INDEX IF NOT EXISTS idx_waitlist_entries_queue_order ON waitlist_entries (resource_id, start_time, end_time, joined_at, id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_waitlist_unique_active ON waitlist_entries (member_id, resource_id, start_time, end_time)
    WHERE status = 'WAITING';

CREATE TABLE IF NOT EXISTS waitlist_offers (
    id UUID PRIMARY KEY,
    waitlist_entry_id UUID NOT NULL REFERENCES waitlist_entries (id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    offered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP,
    resulting_booking_id UUID REFERENCES bookings (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_waitlist_offers_entry ON waitlist_offers (waitlist_entry_id);
