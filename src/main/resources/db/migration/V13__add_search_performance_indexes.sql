-- Indexes to support availability search queries within 1 second under expected load (NFR-1.2, US-3)
CREATE INDEX IF NOT EXISTS idx_resources_active_type ON resources (active, resource_type);
CREATE INDEX IF NOT EXISTS idx_bookings_confirmed_window ON bookings (status, start_time, end_time);

