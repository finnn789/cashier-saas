-- Cohesive MVP persistence foundation. V1 may have been applied before the role
-- collection table existed, so this repair remains safe for existing local databases.
CREATE TABLE IF NOT EXISTS staff_user_roles (
    staff_user_id UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (staff_user_id, role),
    CONSTRAINT ck_staff_user_roles_role CHECK (role IN ('SUPERADMIN', 'OWNERMERCHANT', 'CASHIER'))
);

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'IDR';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Jakarta';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS tax_rate NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS service_charge_rate NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS logo_url VARCHAR(512);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS address VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS phone_number VARCHAR(32);

CREATE TABLE outlets (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), name VARCHAR(120) NOT NULL,
    time_zone VARCHAR(64) NOT NULL, address VARCHAR(255), phone_number VARCHAR(32), active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_outlets_tenant_name UNIQUE (tenant_id, name)
);
CREATE TABLE staff_user_outlet_access (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), staff_user_id UUID NOT NULL REFERENCES staff_users(id),
    outlet_id UUID NOT NULL REFERENCES outlets(id), created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_staff_user_outlet_access UNIQUE (staff_user_id, outlet_id)
);
CREATE TABLE categories (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id),
    name VARCHAR(120) NOT NULL, display_order INTEGER NOT NULL DEFAULT 0, archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, CONSTRAINT uk_categories_outlet_name UNIQUE (outlet_id, name)
);
CREATE TABLE products (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id),
    category_id UUID NOT NULL REFERENCES categories(id), name VARCHAR(160) NOT NULL, description VARCHAR(1000),
    price NUMERIC(19,2) NOT NULL CHECK (price >= 0), image_url VARCHAR(512), available BOOLEAN NOT NULL DEFAULT TRUE,
    archived BOOLEAN NOT NULL DEFAULT FALSE, display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE option_groups (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id),
    product_id UUID NOT NULL REFERENCES products(id), name VARCHAR(120) NOT NULL, required BOOLEAN NOT NULL DEFAULT FALSE,
    minimum_selections INTEGER NOT NULL DEFAULT 0 CHECK (minimum_selections >= 0), maximum_selections INTEGER NOT NULL DEFAULT 1 CHECK (maximum_selections >= minimum_selections),
    display_order INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE product_options (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id),
    option_group_id UUID NOT NULL REFERENCES option_groups(id), name VARCHAR(120) NOT NULL,
    price_adjustment NUMERIC(19,2) NOT NULL DEFAULT 0, available BOOLEAN NOT NULL DEFAULT TRUE, display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE dining_tables (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id),
    name VARCHAR(80) NOT NULL, status VARCHAR(32) NOT NULL CHECK (status IN ('VACANT', 'ACTIVE', 'AWAITING_PAYMENT')),
    active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_dining_tables_outlet_name UNIQUE (outlet_id, name)
);
CREATE TABLE table_qr_tokens (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id),
    dining_table_id UUID NOT NULL UNIQUE REFERENCES dining_tables(id), token VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_table_qr_tokens_token UNIQUE (token)
);
CREATE TABLE sales_orders (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id), dining_table_id UUID REFERENCES dining_tables(id),
    order_number VARCHAR(32) NOT NULL, idempotency_key VARCHAR(128) NOT NULL, source VARCHAR(24) NOT NULL CHECK (source IN ('QR', 'POS')),
    service_type VARCHAR(24) NOT NULL CHECK (service_type IN ('DINE_IN', 'TAKEAWAY')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('NEW', 'CONFIRMED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED')),
    payment_status VARCHAR(24) NOT NULL CHECK (payment_status IN ('UNPAID', 'PAID', 'REFUNDED')),
    customer_label VARCHAR(80), subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal >= 0), tax_amount NUMERIC(19,2) NOT NULL CHECK (tax_amount >= 0),
    service_charge_amount NUMERIC(19,2) NOT NULL CHECK (service_charge_amount >= 0), discount_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    total NUMERIC(19,2) NOT NULL CHECK (total >= 0), cancellation_reason VARCHAR(500), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_sales_orders_outlet_order_number UNIQUE (outlet_id, order_number), CONSTRAINT uk_sales_orders_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);
CREATE TABLE order_items (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id), sales_order_id UUID NOT NULL REFERENCES sales_orders(id),
    product_id UUID REFERENCES products(id), product_name_snapshot VARCHAR(160) NOT NULL, unit_price_snapshot NUMERIC(19,2) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0), line_total NUMERIC(19,2) NOT NULL CHECK (line_total >= 0), note VARCHAR(500), created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE order_item_options (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id), order_item_id UUID NOT NULL REFERENCES order_items(id),
    product_option_id UUID REFERENCES product_options(id), option_name_snapshot VARCHAR(120) NOT NULL, price_adjustment_snapshot NUMERIC(19,2) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE discounts (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id), sales_order_id UUID NOT NULL UNIQUE REFERENCES sales_orders(id),
    type VARCHAR(24) NOT NULL CHECK (type IN ('NOMINAL', 'PERCENTAGE')), value NUMERIC(19,2) NOT NULL CHECK (value >= 0), amount NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    reason VARCHAR(255) NOT NULL, granted_by_staff_user_id UUID NOT NULL REFERENCES staff_users(id), created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE payments (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID NOT NULL REFERENCES outlets(id), sales_order_id UUID NOT NULL REFERENCES sales_orders(id),
    method VARCHAR(32) NOT NULL CHECK (method IN ('CASH', 'MANUAL_NON_CASH')), amount_paid NUMERIC(19,2) NOT NULL CHECK (amount_paid >= 0),
    change_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (change_amount >= 0), receipt_number VARCHAR(32) NOT NULL,
    received_by_staff_user_id UUID NOT NULL REFERENCES staff_users(id), paid_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_payments_sales_order UNIQUE (sales_order_id)
);
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), outlet_id UUID REFERENCES outlets(id), actor_staff_user_id UUID REFERENCES staff_users(id),
    action VARCHAR(80) NOT NULL, entity_type VARCHAR(80) NOT NULL, entity_id UUID NOT NULL, reason VARCHAR(1000), details TEXT, created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_outlets_tenant_id ON outlets(tenant_id);
CREATE INDEX ix_staff_user_outlet_access_tenant_id ON staff_user_outlet_access(tenant_id);
CREATE INDEX ix_categories_tenant_outlet ON categories(tenant_id, outlet_id);
CREATE INDEX ix_products_tenant_outlet_category ON products(tenant_id, outlet_id, category_id);
CREATE INDEX ix_option_groups_tenant_product ON option_groups(tenant_id, product_id);
CREATE INDEX ix_product_options_tenant_group ON product_options(tenant_id, option_group_id);
CREATE INDEX ix_dining_tables_tenant_outlet ON dining_tables(tenant_id, outlet_id);
CREATE INDEX ix_table_qr_tokens_tenant_outlet ON table_qr_tokens(tenant_id, outlet_id);
CREATE INDEX ix_sales_orders_tenant_outlet_status ON sales_orders(tenant_id, outlet_id, status);
CREATE INDEX ix_sales_orders_paid_report ON sales_orders(tenant_id, outlet_id, payment_status, created_at);
CREATE INDEX ix_order_items_tenant_order ON order_items(tenant_id, sales_order_id);
CREATE INDEX ix_order_item_options_tenant_item ON order_item_options(tenant_id, order_item_id);
CREATE INDEX ix_payments_tenant_outlet_paid_at ON payments(tenant_id, outlet_id, paid_at);
CREATE INDEX ix_audit_logs_tenant_created_at ON audit_logs(tenant_id, created_at);
