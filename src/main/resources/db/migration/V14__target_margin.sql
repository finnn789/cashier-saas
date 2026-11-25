-- TASK-085 J1: tenant-wide target gross margin (PRD v3 FR-CS-02), default 60%.
ALTER TABLE public.tenants ADD COLUMN target_margin_pct numeric(5,2) NOT NULL DEFAULT 60.00;
ALTER TABLE public.tenants ADD CONSTRAINT ck_tenants_target_margin_pct CHECK (target_margin_pct >= 0 AND target_margin_pct < 100);
