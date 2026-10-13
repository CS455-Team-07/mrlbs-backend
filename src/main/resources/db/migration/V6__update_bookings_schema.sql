ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS member_id UUID REFERENCES members (id),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'bookings' AND column_name = 'owner_email'
    ) THEN
        UPDATE bookings b
        SET member_id = m.id
        FROM members m
        WHERE b.owner_email = m.email AND b.member_id IS NULL;

        ALTER TABLE bookings ALTER COLUMN owner_email DROP NOT NULL;
    END IF;
END $$;

