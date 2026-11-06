DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'booking_ai_reviews'
          AND column_name = 'confidence_score' AND data_type <> 'double precision'
    ) THEN
        ALTER TABLE booking_ai_reviews ALTER COLUMN confidence_score TYPE DOUBLE PRECISION;
    END IF;
END $$;
