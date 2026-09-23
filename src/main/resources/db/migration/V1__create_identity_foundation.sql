CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE staff_users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_staff_users_email_lower ON staff_users (LOWER(email));

CREATE TABLE staff_user_roles (
    staff_user_id UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (staff_user_id, role),
    CONSTRAINT ck_staff_user_roles_role CHECK (role IN ('SUPERADMIN', 'OWNERMERCHANT', 'CASHIER'))
);
