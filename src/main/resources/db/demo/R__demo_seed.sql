-- Demo seed (profile "demo" only). Idempotent: safe to re-run; every insert is ON CONFLICT DO NOTHING.
-- Accounts (password for both: Demo123!):
--   owner@demo.local   -> OWNERMERCHANT of "Kopi Demo"
--   cashier@demo.local -> CASHIER of "Kopi Demo"
-- Public QR tokens are stored in plain text (table_qr_tokens.token, 43 chars [A-Za-z0-9_-]).

INSERT INTO business_types (id, code, name, is_active, created_at)
VALUES ('00000000-0000-4000-8000-000000000001', 'FNB', 'Food & Beverage', true, now())
ON CONFLICT DO NOTHING;

INSERT INTO staff_users (id, email, password_hash, full_name, status, onboarding_intent, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-0000000000a1', 'owner@demo.local',
  '$2a$10$7aYv8fyMt6blrrbDYedSBe89hQ/WuJDvntvdSgmt87GiNGqEcOQ3u', 'Demo Owner', 'ACTIVE', 'OWNER', now(), now(), 0),
 ('00000000-0000-4000-8000-0000000000a2', 'cashier@demo.local',
  '$2a$10$7aYv8fyMt6blrrbDYedSBe89hQ/WuJDvntvdSgmt87GiNGqEcOQ3u', 'Demo Cashier', 'ACTIVE', 'CASHIER_ONLY', now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO tenants (id, name, currency, time_zone, tax_rate, service_charge_rate, address, phone_number, status,
                     business_type_id, created_by_user_id, profile_completed_at, created_at, updated_at, version)
VALUES ('00000000-0000-4000-8000-0000000000b1', 'Kopi Demo', 'IDR', 'Asia/Jakarta', 11.00, 5.00,
        'Jl. Demo No. 1, Jakarta', '+6281200000000', 'ACTIVE',
        '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000a1', now(), now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO subscriptions (id, tenant_id, plan_code, status, starts_at, ends_at, created_at, updated_at, version)
VALUES ('00000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-0000000000b1', 'FREETIER', 'ACTIVE',
        now() - interval '1 day', NULL, now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO outlets (id, tenant_id, name, time_zone, address, phone_number, active, created_at, updated_at, version)
VALUES ('00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000b1', 'Kopi Demo - Pusat',
        'Asia/Jakarta', 'Jl. Demo No. 1, Jakarta', '+6281200000000', true, now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO tenant_memberships (id, tenant_id, user_id, role, active, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-0000000000e1', '00000000-0000-4000-8000-0000000000b1',
  '00000000-0000-4000-8000-0000000000a1', 'OWNERMERCHANT', true, now(), now(), 0),
 ('00000000-0000-4000-8000-0000000000e2', '00000000-0000-4000-8000-0000000000b1',
  '00000000-0000-4000-8000-0000000000a2', 'CASHIER', true, now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO membership_outlet_access (id, tenant_id, membership_id, outlet_id, created_at)
VALUES
 ('00000000-0000-4000-8000-0000000000f1', '00000000-0000-4000-8000-0000000000b1',
  '00000000-0000-4000-8000-0000000000e1', '00000000-0000-4000-8000-0000000000d1', now()),
 ('00000000-0000-4000-8000-0000000000f2', '00000000-0000-4000-8000-0000000000b1',
  '00000000-0000-4000-8000-0000000000e2', '00000000-0000-4000-8000-0000000000d1', now())
ON CONFLICT DO NOTHING;

-- Categories
INSERT INTO categories (id, tenant_id, outlet_id, name, display_order, archived, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-000000001001', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', 'Kopi', 1, false, now(), now(), 0),
 ('00000000-0000-4000-8000-000000001002', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', 'Non-Kopi', 2, false, now(), now(), 0),
 ('00000000-0000-4000-8000-000000001003', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', 'Makanan', 3, false, now(), now(), 0)
ON CONFLICT DO NOTHING;

-- Products (price in IDR)
INSERT INTO products (id, tenant_id, outlet_id, category_id, name, description, price, available, archived, display_order, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-000000002001', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000001001', 'Espresso', 'Single shot espresso', 18000.00, true, false, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000002002', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000001001', 'Kopi Susu Gula Aren', 'Es kopi susu dengan gula aren', 25000.00, true, false, 2, now(), now(), 0),
 ('00000000-0000-4000-8000-000000002003', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000001001', 'Cappuccino', 'Espresso, susu steam, foam', 28000.00, true, false, 3, now(), now(), 0),
 ('00000000-0000-4000-8000-000000002004', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000001002', 'Matcha Latte', 'Matcha dengan susu', 30000.00, true, false, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000002005', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000001003', 'Croissant Butter', 'Croissant panggang', 22000.00, true, false, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000002006', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000001003', 'Nasi Goreng Kampung', 'Nasi goreng dengan telur', 35000.00, true, false, 2, now(), now(), 0)
ON CONFLICT DO NOTHING;

-- Option groups (Ukuran: required, exactly 1; Tambahan: optional, up to 2)
INSERT INTO option_groups (id, tenant_id, outlet_id, product_id, name, required, minimum_selections, maximum_selections, display_order, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-000000003001', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000002002', 'Ukuran', true, 1, 1, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000003002', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000002002', 'Tambahan', false, 0, 2, 2, now(), now(), 0),
 ('00000000-0000-4000-8000-000000003003', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000002003', 'Ukuran', true, 1, 1, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000003004', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000002004', 'Suhu', true, 1, 1, 1, now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO product_options (id, tenant_id, outlet_id, option_group_id, name, price_adjustment, available, display_order, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-000000004001', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003001', 'Regular', 0.00, true, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004002', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003001', 'Large', 5000.00, true, 2, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004003', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003002', 'Extra Shot', 6000.00, true, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004004', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003002', 'Oat Milk', 8000.00, true, 2, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004005', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003003', 'Regular', 0.00, true, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004006', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003003', 'Large', 5000.00, true, 2, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004007', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003004', 'Panas', 0.00, true, 1, now(), now(), 0),
 ('00000000-0000-4000-8000-000000004008', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000003004', 'Dingin', 2000.00, true, 2, now(), now(), 0)
ON CONFLICT DO NOTHING;

-- Dining tables + QR tokens (tokens are plain text, exactly 43 chars)
INSERT INTO dining_tables (id, tenant_id, outlet_id, name, status, active, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-000000005001', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', 'Meja 01', 'VACANT', true, now(), now(), 0),
 ('00000000-0000-4000-8000-000000005002', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', 'Meja 02', 'VACANT', true, now(), now(), 0),
 ('00000000-0000-4000-8000-000000005003', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', 'Meja 03', 'VACANT', true, now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO table_qr_tokens (id, tenant_id, outlet_id, dining_table_id, qr_type, token, active, created_at, updated_at, version)
VALUES
 ('00000000-0000-4000-8000-000000006001', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000005001', 'TABLE', 'demo-qr-meja-01-kopi-demo-public-token00000', true, now(), now(), 0),
 ('00000000-0000-4000-8000-000000006002', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000005002', 'TABLE', 'demo-qr-meja-02-kopi-demo-public-token00000', true, now(), now(), 0),
 ('00000000-0000-4000-8000-000000006003', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000005003', 'TABLE', 'demo-qr-meja-03-kopi-demo-public-token00000', true, now(), now(), 0),
 ('00000000-0000-4000-8000-000000006004', '00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000d1', NULL, 'OUTLET', 'demo-qr-outlet-kopi-demo-public-token000000', true, now(), now(), 0)
ON CONFLICT DO NOTHING;
