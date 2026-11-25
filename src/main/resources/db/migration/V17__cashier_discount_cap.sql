ALTER TABLE public.tenants ADD COLUMN cashier_max_discount_pct numeric(5,2) NOT NULL DEFAULT 0;
ALTER TABLE public.tenants ADD CONSTRAINT ck_tenants_cashier_max_discount_pct CHECK (cashier_max_discount_pct >= 0 AND cashier_max_discount_pct <= 100);
