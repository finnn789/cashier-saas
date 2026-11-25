-- TASK-100 J1: manual ingredient purchase document (PRD v3 FR-IN-02). Stock and cost change through RESTOCK movements; these rows are the document.

CREATE TABLE public.purchases (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    supplier_name character varying(120),
    purchased_on date NOT NULL,
    note character varying(500),
    total numeric(19,2) NOT NULL,
    actor_membership_id uuid NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    CONSTRAINT purchases_pkey PRIMARY KEY (id),
    CONSTRAINT uk_purchases_tenant_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_purchases_total CHECK (total >= 0),
    CONSTRAINT fk_purchases_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_purchases_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id)
);

CREATE INDEX idx_purchases_outlet_date ON public.purchases (tenant_id, outlet_id, purchased_on DESC, created_at DESC);

CREATE TABLE public.purchase_lines (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    purchase_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    quantity numeric(14,3) NOT NULL,
    unit_cost numeric(19,4) NOT NULL,
    line_total numeric(19,2) NOT NULL,
    movement_id uuid NOT NULL,
    CONSTRAINT purchase_lines_pkey PRIMARY KEY (id),
    CONSTRAINT uk_purchase_lines_purchase_item UNIQUE (purchase_id, inventory_item_id),
    CONSTRAINT ck_purchase_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_purchase_lines_unit_cost CHECK (unit_cost >= 0),
    CONSTRAINT fk_purchase_lines_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_purchase_lines_purchase FOREIGN KEY (purchase_id) REFERENCES public.purchases(id),
    CONSTRAINT fk_purchase_lines_item FOREIGN KEY (inventory_item_id) REFERENCES public.inventory_items(id),
    CONSTRAINT fk_purchase_lines_movement FOREIGN KEY (movement_id) REFERENCES public.inventory_movements(id)
);

CREATE INDEX idx_purchase_lines_item ON public.purchase_lines (tenant_id, inventory_item_id, purchase_id);
