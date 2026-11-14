-- M5: record-only payments (labels instead of a gateway), petty cash, sequential receipt numbers, receipt footer, order note (TASK-078).

CREATE TABLE public.payment_labels (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    name character varying(40) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    CONSTRAINT payment_labels_pkey PRIMARY KEY (id),
    CONSTRAINT fk_payment_labels_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_payment_labels_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id)
);
CREATE UNIQUE INDEX uk_payment_labels_outlet_name ON public.payment_labels (outlet_id, lower(name));

INSERT INTO public.payment_labels (id, created_at, updated_at, version, tenant_id, outlet_id, name, active, display_order)
SELECT gen_random_uuid(), now(), now(), 0, o.tenant_id, o.id, d.name, true, d.display_order
FROM public.outlets o
CROSS JOIN (VALUES ('QRIS', 1), ('Transfer', 2), ('Debit', 3)) AS d(name, display_order);

ALTER TABLE public.payments ADD COLUMN method_label character varying(40);
UPDATE public.payments SET method = 'MANUAL_NON_CASH', method_label = 'Gateway simulasi (lama)' WHERE method = 'MOCK_GATEWAY';
UPDATE public.payment_refunds SET method = 'MANUAL_NON_CASH' WHERE method = 'MOCK_GATEWAY';
UPDATE public.payments SET method_label = 'Non-tunai' WHERE method = 'MANUAL_NON_CASH' AND method_label IS NULL;
ALTER TABLE public.payments DROP CONSTRAINT payments_method_check;
ALTER TABLE public.payments ADD CONSTRAINT payments_method_check CHECK (method IN ('CASH', 'MANUAL_NON_CASH'));

CREATE TABLE public.cash_expenses (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    cash_shift_id uuid NOT NULL,
    amount numeric(19,2) NOT NULL,
    description character varying(200) NOT NULL,
    recorded_by_staff_user_id uuid NOT NULL,
    recorded_at timestamp(6) with time zone NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    CONSTRAINT cash_expenses_pkey PRIMARY KEY (id),
    CONSTRAINT fk_cash_expenses_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_cash_expenses_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id),
    CONSTRAINT fk_cash_expenses_cash_shift FOREIGN KEY (cash_shift_id) REFERENCES public.cash_shifts(id),
    CONSTRAINT fk_cash_expenses_recorded_by FOREIGN KEY (recorded_by_staff_user_id) REFERENCES public.staff_users(id),
    CONSTRAINT uk_cash_expenses_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_cash_expenses_amount CHECK (amount > 0)
);
CREATE INDEX idx_cash_expenses_cash_shift ON public.cash_expenses (cash_shift_id);

-- Gap-free per-outlet daily receipt counter, advanced with INSERT ... ON CONFLICT; not a JPA entity.
CREATE TABLE public.receipt_sequences (
    outlet_id uuid NOT NULL,
    business_date date NOT NULL,
    last_value integer NOT NULL,
    CONSTRAINT receipt_sequences_pkey PRIMARY KEY (outlet_id, business_date),
    CONSTRAINT fk_receipt_sequences_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id)
);

ALTER TABLE public.tenants ADD COLUMN receipt_footer character varying(200);
ALTER TABLE public.sales_orders ADD COLUMN order_note character varying(500);
