-- Versions minimales des applications (GET /api/meta/client-requirements).
-- Valeurs initiales provisoires : à relever à chaque publication qui rompt la compatibilité.
INSERT INTO platform_settings (key, value) VALUES
    ('client.requirements', '[
        {"platform": "ANDROID", "minimumVersion": "1.0.0"},
        {"platform": "WEB",     "minimumVersion": "1.0.0"}
    ]'::jsonb);
