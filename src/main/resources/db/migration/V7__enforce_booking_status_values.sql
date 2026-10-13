ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS bookings_status_check;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check
    CHECK (status IN (
        'DRAFT',
        'CONFIRMED',
        'PENDING_APPROVAL',
        'PENDING',
        'REJECTED',
        'CANCELLED',
        'COMPLETED',
        'IN_PROGRESS'
    ));
