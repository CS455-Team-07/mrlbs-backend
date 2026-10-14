ALTER TABLE waitlist_entries
    ADD COLUMN IF NOT EXISTS credit_cost NUMERIC(12, 0),
    ADD COLUMN IF NOT EXISTS contention_multiplier NUMERIC(8, 2);
