-- Public order access uses a separate short-lived status token; only its digest is stored.
-- Hibernate ddl-auto=update may have added these columns before Flyway ran.
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS public_status_token_digest VARCHAR(64);
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS public_status_token_expires_at TIMESTAMPTZ;
-- A Hibernate-generated UNIQUE constraint on this single column is equivalent to
-- the partial unique index for non-NULL digests. Avoid a redundant second index.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_index AS idx
        JOIN pg_attribute AS attr
          ON attr.attrelid = idx.indrelid AND attr.attnum = idx.indkey[0]
        WHERE idx.indrelid = 'sales_orders'::regclass
          AND idx.indisunique AND idx.indisvalid AND idx.indnkeyatts = 1
          AND attr.attname = 'public_status_token_digest'
          AND (idx.indpred IS NULL OR
               pg_get_expr(idx.indpred, idx.indrelid) = '(public_status_token_digest IS NOT NULL)')
    ) THEN
        CREATE UNIQUE INDEX uk_sales_orders_public_status_token_digest
            ON sales_orders (public_status_token_digest)
            WHERE public_status_token_digest IS NOT NULL;
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_sales_orders_public_token_expiry ON sales_orders (public_status_token_expires_at);
-- POS orders (subsequent slice) do not require a public token or fingerprint.
