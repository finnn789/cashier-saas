-- Retain existing Laundry tenants and transactions for later reactivation.
-- New onboarding and operational access are limited to F&B.
INSERT INTO business_types (id, code, name, is_active, created_at)
VALUES ('00000000-0000-0000-0000-000000000102', 'FNB', 'Food & Beverage', TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, is_active = TRUE;

UPDATE business_types SET is_active = FALSE WHERE code = 'LAUNDRY';
