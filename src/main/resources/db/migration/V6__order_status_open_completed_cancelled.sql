-- Pivot: order status is OPEN (held, unpaid) / COMPLETED (paid) / CANCELLED. Kitchen statuses are gone.
-- fnb_order_status_history has no CHECK constraint, and its historical rows are intentionally left untouched.
ALTER TABLE sales_orders DROP CONSTRAINT sales_orders_status_check;

UPDATE sales_orders SET status = 'COMPLETED'
WHERE status IN ('NEW', 'CONFIRMED', 'PREPARING', 'READY') AND payment_status = 'PAID';

UPDATE sales_orders SET status = 'OPEN'
WHERE status IN ('NEW', 'CONFIRMED', 'PREPARING', 'READY') AND payment_status = 'UNPAID';

-- Fail loudly (e.g. an old status with payment_status REFUNDED) instead of silently remapping money-related rows.
DO $$
DECLARE
    unmapped bigint;
BEGIN
    SELECT count(*) INTO unmapped FROM sales_orders WHERE status NOT IN ('OPEN', 'COMPLETED', 'CANCELLED');
    IF unmapped > 0 THEN
        RAISE EXCEPTION 'V6 aborted: % sales_orders row(s) keep an old status that cannot be mapped automatically', unmapped;
    END IF;
END $$;

ALTER TABLE sales_orders ADD CONSTRAINT sales_orders_status_check CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED'));
