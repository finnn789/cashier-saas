-- Hibernate ddl-auto=update may have already created these @Version columns before Flyway.
-- Preserve existing values while restoring the default required by the migration.
ALTER TABLE categories ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE option_groups ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product_options ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dining_tables ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE table_qr_tokens ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE categories ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE products ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE option_groups ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE product_options ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE dining_tables ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE table_qr_tokens ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE sales_orders ALTER COLUMN version SET DEFAULT 0;

-- Composite keys make cross-tenant/outlet parent references impossible even for direct SQL writes.
ALTER TABLE outlets ADD CONSTRAINT uk_outlets_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE categories ADD CONSTRAINT uk_categories_scope_id UNIQUE (tenant_id, outlet_id, id);
ALTER TABLE products ADD CONSTRAINT uk_products_scope_id UNIQUE (tenant_id, outlet_id, id);
ALTER TABLE option_groups ADD CONSTRAINT uk_option_groups_scope_id UNIQUE (tenant_id, outlet_id, id);
ALTER TABLE product_options ADD CONSTRAINT uk_product_options_scope_id UNIQUE (tenant_id, outlet_id, id);
ALTER TABLE dining_tables ADD CONSTRAINT uk_dining_tables_scope_id UNIQUE (tenant_id, outlet_id, id);
ALTER TABLE sales_orders ADD CONSTRAINT uk_sales_orders_scope_id UNIQUE (tenant_id, outlet_id, id);
ALTER TABLE order_items ADD CONSTRAINT uk_order_items_scope_id UNIQUE (tenant_id, outlet_id, id);

ALTER TABLE categories ADD CONSTRAINT fk_categories_outlet_scope FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets (tenant_id, id);
ALTER TABLE products ADD CONSTRAINT fk_products_category_scope FOREIGN KEY (tenant_id, outlet_id, category_id) REFERENCES categories (tenant_id, outlet_id, id);
ALTER TABLE option_groups ADD CONSTRAINT fk_groups_product_scope FOREIGN KEY (tenant_id, outlet_id, product_id) REFERENCES products (tenant_id, outlet_id, id);
ALTER TABLE product_options ADD CONSTRAINT fk_options_group_scope FOREIGN KEY (tenant_id, outlet_id, option_group_id) REFERENCES option_groups (tenant_id, outlet_id, id);
ALTER TABLE dining_tables ADD CONSTRAINT fk_tables_outlet_scope FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets (tenant_id, id);
ALTER TABLE table_qr_tokens ADD CONSTRAINT fk_qr_table_scope FOREIGN KEY (tenant_id, outlet_id, dining_table_id) REFERENCES dining_tables (tenant_id, outlet_id, id);
ALTER TABLE sales_orders ADD CONSTRAINT fk_orders_outlet_scope FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets (tenant_id, id);
ALTER TABLE sales_orders ADD CONSTRAINT fk_orders_table_scope FOREIGN KEY (tenant_id, outlet_id, dining_table_id) REFERENCES dining_tables (tenant_id, outlet_id, id);
ALTER TABLE order_items ADD CONSTRAINT fk_items_order_scope FOREIGN KEY (tenant_id, outlet_id, sales_order_id) REFERENCES sales_orders (tenant_id, outlet_id, id);
ALTER TABLE order_items ADD CONSTRAINT fk_items_product_scope FOREIGN KEY (tenant_id, outlet_id, product_id) REFERENCES products (tenant_id, outlet_id, id);
ALTER TABLE order_item_options ADD CONSTRAINT fk_item_options_item_scope FOREIGN KEY (tenant_id, outlet_id, order_item_id) REFERENCES order_items (tenant_id, outlet_id, id);
ALTER TABLE order_item_options ADD CONSTRAINT fk_item_options_option_scope FOREIGN KEY (tenant_id, outlet_id, product_option_id) REFERENCES product_options (tenant_id, outlet_id, id);
