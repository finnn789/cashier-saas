-- Free-tier onboarding and a simple paid-at-counter sale, separate from the
-- later laundry production/order lifecycle. V1-V4 remain unchanged.
CREATE TABLE IF NOT EXISTS business_types (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS business_type_id UUID REFERENCES business_types(id);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS created_by_user_id UUID REFERENCES staff_users(id);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS onboarding_idempotency_key VARCHAR(128);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS onboarding_request_fingerprint CHAR(64);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS profile_completed_at TIMESTAMPTZ;
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_status
    CHECK (status IN ('DRAFT', 'PENDING_PROFILE', 'ACTIVE', 'SUSPENDED', 'CLOSED'));
CREATE UNIQUE INDEX IF NOT EXISTS ux_tenants_onboarding_key
    ON tenants(created_by_user_id, onboarding_idempotency_key)
    WHERE onboarding_idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_tenants_tenant_id ON tenants(id);

ALTER TABLE outlets ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS ux_outlets_tenant_id ON outlets(tenant_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_memberships_tenant_id ON tenant_memberships(tenant_id, id);

CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    plan_code VARCHAR(40) NOT NULL CHECK (plan_code = 'FREETIER'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
-- Older local Hibernate-created schemas may contain the unused plan-version
-- columns. Keep any historical rows intact and refuse to relabel them.
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS plan_code VARCHAR(40);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM subscriptions WHERE plan_code IS NULL) THEN
        RAISE EXCEPTION 'Existing subscriptions require a reviewed plan-code migration.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'subscriptions'
                 AND column_name = 'plan_version_id') THEN
        ALTER TABLE subscriptions ALTER COLUMN plan_version_id DROP NOT NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'subscriptions'
                 AND column_name = 'plan_price_id') THEN
        ALTER TABLE subscriptions ALTER COLUMN plan_price_id DROP NOT NULL;
    END IF;
END $$;
ALTER TABLE subscriptions ALTER COLUMN plan_code SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_subscriptions_one_live_per_tenant
    ON subscriptions(tenant_id) WHERE status IN ('PENDING_PAYMENT', 'ACTIVE', 'PAST_DUE');

CREATE TABLE IF NOT EXISTS membership_outlet_access (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    membership_id UUID NOT NULL,
    outlet_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (membership_id, outlet_id),
    FOREIGN KEY (tenant_id, membership_id) REFERENCES tenant_memberships(tenant_id, id),
    FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id)
);

CREATE TABLE cashier_catalog_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    outlet_id UUID NOT NULL,
    name VARCHAR(140) NOT NULL,
    billing_unit VARCHAR(10) NOT NULL CHECK (billing_unit IN ('KG', 'PIECE')),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, outlet_id, id),
    UNIQUE (tenant_id, outlet_id, name),
    FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id)
);

CREATE TABLE cashier_sales (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    outlet_id UUID NOT NULL,
    sale_number VARCHAR(50) NOT NULL,
    customer_name VARCHAR(160) NOT NULL,
    subtotal_amount NUMERIC(19,2) NOT NULL CHECK (subtotal_amount > 0),
    total_amount NUMERIC(19,2) NOT NULL CHECK (total_amount = subtotal_amount),
    currency_code CHAR(3) NOT NULL,
    payment_method VARCHAR(16) NOT NULL CHECK (payment_method IN ('CASH', 'NON_CASH')),
    tendered_amount NUMERIC(19,2) NOT NULL CHECK (tendered_amount >= total_amount),
    change_amount NUMERIC(19,2) NOT NULL CHECK (change_amount = tendered_amount - total_amount),
    payment_reference VARCHAR(120),
    received_by_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, outlet_id, id),
    UNIQUE (tenant_id, outlet_id, sale_number),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id),
    FOREIGN KEY (tenant_id, received_by_membership_id) REFERENCES tenant_memberships(tenant_id, id)
);
CREATE INDEX ix_cashier_sales_report ON cashier_sales(tenant_id, outlet_id, paid_at DESC);

CREATE TABLE cashier_sale_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    outlet_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    catalog_item_id UUID NOT NULL,
    name_snapshot VARCHAR(140) NOT NULL,
    billing_unit_snapshot VARCHAR(10) NOT NULL,
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    unit_price_snapshot NUMERIC(19,2) NOT NULL CHECK (unit_price_snapshot >= 0),
    line_total_amount NUMERIC(19,2) NOT NULL CHECK (line_total_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id, outlet_id, sale_id) REFERENCES cashier_sales(tenant_id, outlet_id, id),
    FOREIGN KEY (tenant_id, outlet_id, catalog_item_id) REFERENCES cashier_catalog_items(tenant_id, outlet_id, id),
    CHECK (billing_unit_snapshot <> 'PIECE' OR quantity = trunc(quantity))
);

INSERT INTO business_types(id, code, name, is_active, created_at) VALUES
    ('00000000-0000-0000-0000-000000000101', 'LAUNDRY', 'Laundry', TRUE, CURRENT_TIMESTAMP)
    ON CONFLICT (code) DO NOTHING;
