-- Reusable option groups (TASK-064): groups become an outlet-level library, attached to products through a join table.
-- Groups are archived (soft), never deleted: order_item_options.option_group_id keeps referencing option_groups(id).
ALTER TABLE public.option_groups ADD COLUMN archived boolean NOT NULL DEFAULT false;

CREATE TABLE public.product_option_groups (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    product_id uuid NOT NULL,
    option_group_id uuid NOT NULL,
    display_order integer NOT NULL,
    CONSTRAINT product_option_groups_pkey PRIMARY KEY (id),
    CONSTRAINT uk_product_option_groups_product_group UNIQUE (product_id, option_group_id),
    CONSTRAINT fk_product_option_groups_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenants(id),
    CONSTRAINT fk_product_option_groups_outlet FOREIGN KEY (outlet_id) REFERENCES public.outlets(id),
    CONSTRAINT fk_product_option_groups_product FOREIGN KEY (product_id) REFERENCES public.products(id),
    CONSTRAINT fk_product_option_groups_option_group FOREIGN KEY (option_group_id) REFERENCES public.option_groups(id)
);

CREATE INDEX idx_product_option_groups_group ON public.product_option_groups (option_group_id);

-- Backfill: every existing group becomes a library group attached to its original product.
INSERT INTO public.product_option_groups (id, created_at, tenant_id, outlet_id, product_id, option_group_id, display_order)
SELECT gen_random_uuid(), now(), tenant_id, outlet_id, product_id, id, display_order FROM public.option_groups;

-- Dropping the column also drops its foreign key to products.
ALTER TABLE public.option_groups DROP COLUMN product_id;
