-- TASK-084 J1: stock opname header and per-item lines (PRD v3 FR-IN-03). The leakage report reads these rows plus the movement ledger.
-- Counts recorded before this migration have no header and are ignored by the report (no backfill).

CREATE TABLE public.stock_counts (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    counted_at timestamp(6) with time zone NOT NULL,
    actor_membership_id uuid NOT NULL,
    note character varying(500),
    idempotency_key character varying(128) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    CONSTRAINT stock_counts_pkey PRIMARY KEY (id),
    CONSTRAINT uk_stock_counts_tenant_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_stock_counts_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_stock_counts_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id)
);

CREATE INDEX idx_stock_counts_outlet_time ON public.stock_counts (tenant_id, outlet_id, counted_at DESC);

-- One line per counted item, zero-difference lines included (movement_id is null then).
CREATE TABLE public.stock_count_lines (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    stock_count_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    previous_quantity numeric(14,3) NOT NULL,
    counted_quantity numeric(14,3) NOT NULL,
    difference numeric(14,3) NOT NULL,
    unit_cost numeric(19,4),
    movement_id uuid,
    CONSTRAINT stock_count_lines_pkey PRIMARY KEY (id),
    CONSTRAINT uk_stock_count_lines_count_item UNIQUE (stock_count_id, inventory_item_id),
    CONSTRAINT ck_stock_count_lines_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT fk_stock_count_lines_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_stock_count_lines_count FOREIGN KEY (stock_count_id) REFERENCES public.stock_counts(id),
    CONSTRAINT fk_stock_count_lines_item FOREIGN KEY (inventory_item_id) REFERENCES public.inventory_items(id),
    CONSTRAINT fk_stock_count_lines_movement FOREIGN KEY (movement_id) REFERENCES public.inventory_movements(id)
);

CREATE INDEX idx_stock_count_lines_item ON public.stock_count_lines (tenant_id, inventory_item_id, stock_count_id);
