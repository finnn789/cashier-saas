-- TASK-105 J1: self-service subscription checkout (mock payment gateway). The billing tables from V1 were never written.

ALTER TABLE public.subscriptions
    ADD CONSTRAINT ck_subscriptions_status CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED'));

ALTER TABLE public.platform_invoices
    ADD COLUMN kind varchar(20) NOT NULL,
    ADD COLUMN plan_code varchar(40),
    ADD COLUMN billing_cycle varchar(10),
    ADD COLUMN period_months integer,
    ADD COLUMN extra_outlets integer NOT NULL DEFAULT 0,
    ADD COLUMN ai_questions integer NOT NULL DEFAULT 0,
    ADD COLUMN cancelled_at timestamp(6) with time zone,
    ADD COLUMN cancel_reason varchar(500),
    ADD CONSTRAINT ck_platform_invoices_status CHECK (status IN ('ISSUED', 'PAID', 'CANCELLED', 'EXPIRED')),
    ADD CONSTRAINT ck_platform_invoices_kind CHECK (kind IN ('SUBSCRIPTION', 'AI_TOPUP')),
    ADD CONSTRAINT ck_platform_invoices_cycle CHECK (billing_cycle IS NULL OR billing_cycle IN ('MONTHLY', 'YEARLY'));

CREATE INDEX idx_platform_invoices_tenant_issued ON public.platform_invoices (tenant_id, issued_at DESC);
CREATE INDEX idx_platform_payment_attempts_invoice ON public.platform_payment_attempts (platform_invoice_id);
