ALTER TABLE tenants ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING_PROFILE', 'ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX uk_users_email_lower ON users (LOWER(email));

CREATE TABLE tenant_memberships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tenant_memberships_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('OWNERMERCHANT', 'CASHIER'))
);

CREATE INDEX ix_tenant_memberships_user_active ON tenant_memberships(user_id, active);
