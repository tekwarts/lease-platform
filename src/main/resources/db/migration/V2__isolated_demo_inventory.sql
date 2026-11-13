ALTER TABLE property ADD COLUMN demo_owner_id VARCHAR(128);
ALTER TABLE property ADD COLUMN lab_property BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_property_demo_inventory ON property(demo_owner_id, created_at)
    WHERE lab_property = FALSE;
