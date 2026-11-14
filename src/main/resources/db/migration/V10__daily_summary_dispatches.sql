-- M7: one owner daily-summary dispatch per outlet per business day (TASK-080 J3).

CREATE TABLE public.daily_summary_dispatches (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    business_date date NOT NULL,
    trigger character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    last_attempt_at timestamp(6) with time zone,
    CONSTRAINT daily_summary_dispatches_pkey PRIMARY KEY (id),
    CONSTRAINT fk_daily_summary_dispatches_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_daily_summary_dispatches_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id),
    CONSTRAINT uk_daily_summary_dispatches_outlet_date UNIQUE (outlet_id, business_date),
    CONSTRAINT ck_daily_summary_dispatches_trigger CHECK (trigger IN ('SHIFT_CLOSED', 'FALLBACK')),
    CONSTRAINT ck_daily_summary_dispatches_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX idx_daily_summary_dispatches_status ON public.daily_summary_dispatches (status);
