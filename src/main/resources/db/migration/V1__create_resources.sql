CREATE TABLE resources (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    location VARCHAR(255) NOT NULL,
    capacity INTEGER CHECK (capacity IS NULL OR capacity > 0)
);

CREATE TABLE resource_rules (
    resource_id UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    rule_order INTEGER NOT NULL,
    rule_text VARCHAR(1000) NOT NULL,
    PRIMARY KEY (resource_id, rule_order)
);
