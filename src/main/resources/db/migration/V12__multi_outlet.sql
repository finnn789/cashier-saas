-- TASK-083 J1: multi-outlet. The outlet add-on is activated by an operator on the paid subscription row.

ALTER TABLE public.subscriptions ADD COLUMN extra_outlets integer NOT NULL DEFAULT 0;
ALTER TABLE public.subscriptions
    ADD CONSTRAINT ck_subscriptions_extra_outlets CHECK (extra_outlets >= 0 AND (extra_outlets = 0 OR plan_code <> 'FREETIER'));

CREATE INDEX idx_outlets_tenant_active_created ON public.outlets (tenant_id, active, created_at);
