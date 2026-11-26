-- TASK-106 J1: SUPERADMIN identity. Set only by an operator with SQL (docs/ops/plan-activation.md); no endpoint creates admins.

ALTER TABLE public.staff_users
    ADD COLUMN platform_admin boolean NOT NULL DEFAULT false;
