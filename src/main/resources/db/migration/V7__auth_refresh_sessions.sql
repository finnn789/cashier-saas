CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES staff_users(id),
    tenant_id UUID,
    outlet_id UUID,
    membership_id UUID,
    role VARCHAR(32),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_auth_session_scope CHECK (
        (tenant_id IS NULL AND outlet_id IS NULL AND membership_id IS NULL AND role IS NULL)
        OR (tenant_id IS NOT NULL AND outlet_id IS NOT NULL AND membership_id IS NOT NULL AND role IS NOT NULL)),
    CONSTRAINT ck_auth_session_role CHECK (role IS NULL OR role IN ('OWNERMERCHANT', 'CASHIER')),
    FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id),
    FOREIGN KEY (tenant_id, membership_id) REFERENCES tenant_memberships(tenant_id, id)
);
CREATE INDEX ix_auth_sessions_user ON auth_sessions(user_id, expires_at DESC);

CREATE TABLE auth_refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_sessions(id),
    token_digest CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_auth_refresh_tokens_session ON auth_refresh_tokens(session_id, created_at DESC);
