-- M6: moving-average unit cost, movement unit cost and per-item COGS snapshot (TASK-079). No backfill: old rows stay null.

ALTER TABLE public.inventory_balances ADD COLUMN average_unit_cost numeric(19,4);
ALTER TABLE public.inventory_balances ADD CONSTRAINT ck_inventory_balances_average_unit_cost CHECK (average_unit_cost IS NULL OR average_unit_cost >= 0);

ALTER TABLE public.inventory_movements ADD COLUMN unit_cost numeric(19,4);
ALTER TABLE public.inventory_movements ADD CONSTRAINT ck_inventory_movements_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0);

ALTER TABLE public.order_items ADD COLUMN cost_amount numeric(19,2);
ALTER TABLE public.order_items ADD CONSTRAINT ck_order_items_cost_amount CHECK (cost_amount IS NULL OR cost_amount >= 0);
