ALTER TABLE resources
    ADD COLUMN minimum_duration_hours NUMERIC(8, 2) NOT NULL DEFAULT 1.00
        CHECK (minimum_duration_hours > 0),
    ADD COLUMN maximum_contention_multiplier NUMERIC(8, 2) NOT NULL DEFAULT 3.00
        CHECK (maximum_contention_multiplier >= 0);
