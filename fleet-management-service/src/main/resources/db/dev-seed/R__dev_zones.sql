-- DEV ONLY (loaded by the "dev" profile). Demo zones around central Vienna,
-- matching the coordinates used in fleet-api.yaml examples. Safe to re-run.
INSERT INTO zones (id, name, center_latitude, center_longitude, radius_meters) VALUES
    ('zone_001', 'City Center',   48.2100, 16.3600, 800),
    ('zone_002', 'South Station', 48.1858, 16.3758, 800)
ON CONFLICT (id) DO NOTHING;
