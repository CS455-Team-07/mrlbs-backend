ALTER TABLE waitlist_entries
    DROP CONSTRAINT IF EXISTS waitlist_entries_status_check;

ALTER TABLE waitlist_entries
    ADD CONSTRAINT waitlist_entries_status_check
    CHECK (status IN (
        'WAITING',
        'OFFERED',
        'ALLOCATED',
        'EXPIRED',
        'LEFT',
        'DRAFT',
        'CONFIRMED',
        'PENDING_APPROVAL',
        'PENDING',
        'REJECTED',
        'CANCELLED',
        'COMPLETED',
        'IN_PROGRESS'
    ));

UPDATE waitlist_entries SET status = 'IN_PROGRESS' WHERE status = 'WAITING';
UPDATE waitlist_entries SET status = 'CONFIRMED' WHERE status = 'ALLOCATED';
UPDATE waitlist_entries SET status = 'CANCELLED' WHERE status = 'LEFT';

DROP INDEX IF EXISTS idx_waitlist_unique_active;
CREATE UNIQUE INDEX IF NOT EXISTS idx_waitlist_unique_active ON waitlist_entries (member_id, resource_id, start_time, end_time)
    WHERE status IN ('IN_PROGRESS', 'WAITING');

ALTER TABLE waitlist_offers
    DROP CONSTRAINT IF EXISTS waitlist_offers_status_check;

ALTER TABLE waitlist_offers
    ADD CONSTRAINT waitlist_offers_status_check
    CHECK (status IN (
        'PENDING',
        'ACCEPTED',
        'DECLINED',
        'EXPIRED'
    ));
