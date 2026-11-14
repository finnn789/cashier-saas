-- TASK-082 J1: plan entitlements (Free / Pro / Premium), Premium trial, monthly AI usage.

ALTER TABLE public.subscriptions
    ADD CONSTRAINT ck_subscriptions_plan_code CHECK (plan_code IN ('FREETIER', 'PRO', 'PREMIUM'));
CREATE INDEX idx_subscriptions_tenant_status ON public.subscriptions (tenant_id, status);

ALTER TABLE public.tenants ADD COLUMN premium_trial_started_at timestamp(6) with time zone;

CREATE TABLE public.ai_usage_periods (
    tenant_id uuid NOT NULL,
    period_month date NOT NULL,
    questions_used integer NOT NULL DEFAULT 0,
    bonus_questions integer NOT NULL DEFAULT 0,
    day_date date NOT NULL,
    day_questions integer NOT NULL DEFAULT 0,
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ai_usage_periods_pkey PRIMARY KEY (tenant_id, period_month),
    CONSTRAINT fk_ai_usage_periods_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT ck_ai_usage_periods_counters CHECK (questions_used >= 0 AND bonus_questions >= 0 AND day_questions >= 0
        AND input_tokens >= 0 AND output_tokens >= 0)
);
