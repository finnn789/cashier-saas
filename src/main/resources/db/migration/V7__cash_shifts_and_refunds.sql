-- M4: cash shifts (open/close with cash reconciliation), payments linked to a shift, and owner refunds (TASK-077).

CREATE TABLE public.cash_shifts (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    opened_by_staff_user_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    opening_cash numeric(19,2) NOT NULL,
    opened_at timestamp(6) with time zone NOT NULL,
    closed_by_staff_user_id uuid,
    closed_at timestamp(6) with time zone,
    expected_cash numeric(19,2),
    counted_cash numeric(19,2),
    cash_difference numeric(19,2),
    closing_note character varying(500),
    CONSTRAINT cash_shifts_pkey PRIMARY KEY (id),
    CONSTRAINT fk_cash_shifts_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_cash_shifts_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id),
    CONSTRAINT fk_cash_shifts_membership FOREIGN KEY (membership_id) REFERENCES public.tenant_memberships(id),
    CONSTRAINT fk_cash_shifts_opened_by FOREIGN KEY (opened_by_staff_user_id) REFERENCES public.staff_users(id),
    CONSTRAINT fk_cash_shifts_closed_by FOREIGN KEY (closed_by_staff_user_id) REFERENCES public.staff_users(id),
    CONSTRAINT ck_cash_shifts_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_cash_shifts_opening_cash CHECK (opening_cash >= 0),
    CONSTRAINT ck_cash_shifts_counted_cash CHECK (counted_cash >= 0)
);

-- One open shift per cashier per outlet.
CREATE UNIQUE INDEX uk_cash_shifts_open_per_member ON public.cash_shifts (outlet_id, membership_id) WHERE status = 'OPEN';
CREATE INDEX idx_cash_shifts_outlet_opened ON public.cash_shifts (tenant_id, outlet_id, opened_at);

-- Historical payments have no shift.
ALTER TABLE public.payments
    ADD COLUMN cash_shift_id uuid,
    ADD CONSTRAINT fk_payments_cash_shift FOREIGN KEY (cash_shift_id) REFERENCES public.cash_shifts(id);
CREATE INDEX idx_payments_cash_shift ON public.payments (cash_shift_id) WHERE cash_shift_id IS NOT NULL;

CREATE TABLE public.payment_refunds (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    sales_order_id uuid NOT NULL,
    amount numeric(19,2) NOT NULL,
    method character varying(32) NOT NULL,
    reason character varying(500) NOT NULL,
    refunded_by_staff_user_id uuid NOT NULL,
    cash_shift_id uuid,
    refunded_at timestamp(6) with time zone NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    CONSTRAINT payment_refunds_pkey PRIMARY KEY (id),
    CONSTRAINT fk_payment_refunds_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_payment_refunds_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id),
    CONSTRAINT fk_payment_refunds_payment FOREIGN KEY (payment_id) REFERENCES public.payments(id),
    CONSTRAINT fk_payment_refunds_sales_order FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id),
    CONSTRAINT fk_payment_refunds_refunded_by FOREIGN KEY (refunded_by_staff_user_id) REFERENCES public.staff_users(id),
    CONSTRAINT fk_payment_refunds_cash_shift FOREIGN KEY (cash_shift_id) REFERENCES public.cash_shifts(id),
    CONSTRAINT uk_payment_refunds_payment UNIQUE (payment_id),
    CONSTRAINT uk_payment_refunds_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_payment_refunds_amount CHECK (amount > 0)
);
CREATE INDEX idx_payment_refunds_cash_shift ON public.payment_refunds (cash_shift_id) WHERE cash_shift_id IS NOT NULL;

-- Refunds restore stock through SALE_REVERSAL movements.
ALTER TABLE public.inventory_movements DROP CONSTRAINT ck_inventory_movements_type;
ALTER TABLE public.inventory_movements
    ADD CONSTRAINT ck_inventory_movements_type CHECK (movement_type IN ('RESTOCK', 'WASTE', 'ADJUSTMENT', 'SALE', 'SALE_REVERSAL'));
