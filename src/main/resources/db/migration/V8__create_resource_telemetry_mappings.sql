CREATE TABLE resource_telemetry_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id UUID NOT NULL UNIQUE REFERENCES resources(id) ON DELETE CASCADE,
    external_resource_id VARCHAR(100) NOT NULL UNIQUE,
    telemetry_source VARCHAR(64) NOT NULL DEFAULT 'DEMO_TELEMETRY_SERVICE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_resource_telemetry_mappings_ext_id ON resource_telemetry_mappings(external_resource_id);
