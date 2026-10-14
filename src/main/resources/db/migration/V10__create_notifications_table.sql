CREATE TABLE IF NOT EXISTS in_app_notifications (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    offer_id UUID REFERENCES waitlist_offers (id) ON DELETE CASCADE,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_member ON in_app_notifications (member_id, is_read, created_at);
