-- F&B ingredient inventory, recipes and stock warnings (TASK-061).
-- Stock may legitimately go negative (a sale is never blocked by stock), so there is NO check on inventory_balances.quantity_on_hand.

-- Recipe lines: a line belongs to exactly one product OR one product option (add-on) and consumes one ingredient.
CREATE TABLE public.recipe_lines (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    product_id uuid,
    product_option_id uuid,
    inventory_item_id uuid NOT NULL,
    quantity_per_unit numeric(14,3) NOT NULL,
    CONSTRAINT recipe_lines_pkey PRIMARY KEY (id),
    CONSTRAINT fk_recipe_lines_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_recipe_lines_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id),
    CONSTRAINT fk_recipe_lines_product FOREIGN KEY (product_id) REFERENCES public.products(id),
    CONSTRAINT fk_recipe_lines_product_option FOREIGN KEY (product_option_id) REFERENCES public.product_options(id),
    CONSTRAINT fk_recipe_lines_inventory_item FOREIGN KEY (inventory_item_id) REFERENCES public.inventory_items(id),
    CONSTRAINT ck_recipe_lines_target CHECK ((product_id IS NULL) <> (product_option_id IS NULL)),
    CONSTRAINT ck_recipe_lines_quantity CHECK (quantity_per_unit > 0)
);

CREATE UNIQUE INDEX uk_recipe_lines_product_item ON public.recipe_lines (product_id, inventory_item_id) WHERE product_id IS NOT NULL;
CREATE UNIQUE INDEX uk_recipe_lines_option_item ON public.recipe_lines (product_option_id, inventory_item_id) WHERE product_option_id IS NOT NULL;
CREATE INDEX idx_recipe_lines_inventory_item ON public.recipe_lines (inventory_item_id);

-- Movements: restrict the type to the application enum and index the two read paths (item history, order lookup).
ALTER TABLE public.inventory_movements
    ADD CONSTRAINT ck_inventory_movements_type CHECK (movement_type IN ('RESTOCK', 'WASTE', 'ADJUSTMENT', 'SALE'));

CREATE INDEX idx_inventory_movements_item_time
    ON public.inventory_movements (tenant_id, outlet_id, inventory_item_id, occurred_at DESC);
CREATE INDEX idx_inventory_movements_order
    ON public.inventory_movements (order_id) WHERE order_id IS NOT NULL;

-- Ingredient names are unique per outlet.
ALTER TABLE public.inventory_items
    ADD CONSTRAINT uk_inventory_items_outlet_name UNIQUE (outlet_id, name);
