--
-- PostgreSQL database dump
--



-- Dumped from database version 18.3
-- Dumped by pg_dump version 18.3

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS public;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    created_at timestamp(6) with time zone NOT NULL,
    actor_staff_user_id uuid,
    entity_id uuid NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid,
    tenant_id uuid NOT NULL,
    action character varying(80) NOT NULL,
    entity_type character varying(80) NOT NULL,
    reason character varying(1000),
    details text
);


--
-- Name: auth_refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_refresh_tokens (
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    id uuid NOT NULL,
    session_id uuid NOT NULL,
    token_digest character varying(64) NOT NULL
);


--
-- Name: auth_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_sessions (
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    membership_id uuid,
    outlet_id uuid,
    tenant_id uuid,
    user_id uuid NOT NULL,
    role character varying(32),
    CONSTRAINT auth_sessions_role_check CHECK (((role)::text = ANY ((ARRAY['SUPERADMIN'::character varying, 'OWNERMERCHANT'::character varying, 'CASHIER'::character varying])::text[])))
);


--
-- Name: business_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.business_types (
    is_active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    code character varying(40) NOT NULL,
    name character varying(80) NOT NULL
);


--
-- Name: cashier_catalog_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cashier_catalog_items (
    active boolean NOT NULL,
    unit_price numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    billing_unit character varying(10) NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(140) NOT NULL
);


--
-- Name: cashier_invitations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cashier_invitations (
    claimed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    claimed_by_user_id uuid,
    id uuid NOT NULL,
    invited_by_membership_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code_digest character varying(64) NOT NULL,
    invited_email character varying(320) NOT NULL
);


--
-- Name: cashier_sale_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cashier_sale_items (
    line_total_amount numeric(19,2) NOT NULL,
    quantity numeric(14,3) NOT NULL,
    unit_price_snapshot numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    billing_unit_snapshot character varying(10) NOT NULL,
    catalog_item_id uuid NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    sale_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name_snapshot character varying(140) NOT NULL
);


--
-- Name: cashier_sales; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cashier_sales (
    change_amount numeric(19,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    subtotal_amount numeric(19,2) NOT NULL,
    tendered_amount numeric(19,2) NOT NULL,
    total_amount numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    paid_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    payment_method character varying(16) NOT NULL,
    received_by_membership_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    sale_number character varying(50) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    payment_reference character varying(120),
    idempotency_key character varying(128) NOT NULL,
    customer_name character varying(160) NOT NULL
);


--
-- Name: categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.categories (
    archived boolean NOT NULL,
    display_order integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(120) NOT NULL
);


--
-- Name: customer_addresses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_addresses (
    is_default boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    customer_id uuid NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recipient_phone character varying(40) NOT NULL,
    label character varying(80),
    recipient_name character varying(160) NOT NULL,
    address_text text NOT NULL
);


--
-- Name: customer_invoice_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_invoice_lines (
    line_total_amount numeric(19,2) NOT NULL,
    quantity_snapshot numeric(14,3) NOT NULL,
    unit_price_snapshot numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    customer_invoice_id uuid NOT NULL,
    id uuid NOT NULL,
    laundry_order_item_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    description_snapshot character varying(240) NOT NULL
);


--
-- Name: customer_invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_invoices (
    currency_code character varying(3) NOT NULL,
    discount_amount numeric(19,2) NOT NULL,
    fee_amount numeric(19,2) NOT NULL,
    subtotal_amount numeric(19,2) NOT NULL,
    tax_amount numeric(19,2) NOT NULL,
    total_amount numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    issued_at timestamp(6) with time zone NOT NULL,
    paid_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    invoice_number character varying(50) NOT NULL
);


--
-- Name: customer_payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer_payments (
    amount numeric(19,2) NOT NULL,
    change_amount numeric(19,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    tendered_amount numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    paid_at timestamp(6) with time zone NOT NULL,
    customer_invoice_id uuid NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    received_by_membership_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    method character varying(20) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    reference_number character varying(120),
    idempotency_key character varying(128) NOT NULL
);


--
-- Name: customers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customers (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    phone character varying(40) NOT NULL,
    name character varying(160) NOT NULL,
    email character varying(320),
    notes character varying(1000)
);


--
-- Name: dining_tables; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dining_tables (
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(32) NOT NULL,
    name character varying(80) NOT NULL,
    CONSTRAINT dining_tables_status_check CHECK (((status)::text = ANY ((ARRAY['VACANT'::character varying, 'ACTIVE'::character varying, 'AWAITING_PAYMENT'::character varying])::text[])))
);


--
-- Name: discounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.discounts (
    amount numeric(19,2) NOT NULL,
    value numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    granted_by_staff_user_id uuid NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    sales_order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    type character varying(24) NOT NULL,
    reason character varying(255) NOT NULL,
    CONSTRAINT discounts_type_check CHECK (((type)::text = ANY ((ARRAY['NOMINAL'::character varying, 'PERCENTAGE'::character varying])::text[])))
);


--
-- Name: fnb_gateway_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fnb_gateway_attempts (
    amount numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    resolved_at timestamp(6) with time zone,
    id uuid NOT NULL,
    initiated_by_staff_user_id uuid NOT NULL,
    outcome character varying(16) NOT NULL,
    outlet_id uuid NOT NULL,
    resolved_by_staff_user_id uuid,
    sales_order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    provider_reference character varying(128),
    CONSTRAINT fnb_gateway_attempts_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: fnb_order_status_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fnb_order_status_history (
    created_at timestamp(6) with time zone NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    actor_membership_id uuid,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    sales_order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    from_status character varying(24),
    to_status character varying(24) NOT NULL,
    reason text
);


--
-- Name: gateway_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.gateway_events (
    currency_code character varying(3),
    normalized_amount numeric(19,2),
    created_at timestamp(6) with time zone NOT NULL,
    processed_at timestamp(6) with time zone,
    received_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    matched_attempt_id uuid,
    processing_status character varying(16) NOT NULL,
    normalized_status character varying(20),
    provider_code character varying(50) NOT NULL,
    payload_digest character varying(64) NOT NULL,
    event_type character varying(100) NOT NULL,
    provider_account_ref character varying(120) NOT NULL,
    provider_event_id character varying(160) NOT NULL,
    provider_payment_ref character varying(160)
);


--
-- Name: inventory_balances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_balances (
    quantity_on_hand numeric(14,3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL
);


--
-- Name: inventory_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_items (
    active boolean NOT NULL,
    reorder_level numeric(14,3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    unit character varying(30) NOT NULL,
    sku character varying(80),
    name character varying(140) NOT NULL
);


--
-- Name: inventory_movements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inventory_movements (
    quantity_delta numeric(14,3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    actor_membership_id uuid NOT NULL,
    id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    movement_type character varying(16) NOT NULL,
    order_id uuid,
    outlet_id uuid NOT NULL,
    reversal_of_movement_id uuid,
    tenant_id uuid NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    reason text NOT NULL
);


--
-- Name: laundry_order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.laundry_order_items (
    actual_quantity numeric(14,3),
    billed_quantity numeric(14,3) NOT NULL,
    estimated_duration_minutes integer,
    line_total_amount numeric(19,2) NOT NULL,
    minimum_quantity_snapshot numeric(14,3) NOT NULL,
    quantity_increment_snapshot numeric(14,3) NOT NULL,
    unit_price_snapshot numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    estimated_ready_at timestamp(6) with time zone,
    billing_unit_snapshot character varying(10) NOT NULL,
    id uuid NOT NULL,
    laundry_order_id uuid NOT NULL,
    laundry_service_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    source_price_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    pricing_rule_snapshot character varying(24) NOT NULL,
    service_name_snapshot character varying(140) NOT NULL,
    condition_notes text,
    item_description text
);


--
-- Name: laundry_order_status_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.laundry_order_status_history (
    created_at timestamp(6) with time zone NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    actor_membership_id uuid NOT NULL,
    from_status character varying(16),
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    to_status character varying(16) NOT NULL,
    reason text
);


--
-- Name: laundry_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.laundry_orders (
    created_at timestamp(6) with time zone NOT NULL,
    estimated_ready_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    intake_notes text
);


--
-- Name: laundry_service_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.laundry_service_categories (
    active boolean NOT NULL,
    sort_order integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(100) NOT NULL
);


--
-- Name: laundry_services; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.laundry_services (
    active boolean NOT NULL,
    minimum_quantity numeric(14,3) NOT NULL,
    quantity_increment numeric(14,3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    billing_unit character varying(10) NOT NULL,
    category_id uuid,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(140) NOT NULL
);


--
-- Name: membership_outlet_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.membership_outlet_access (
    created_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    membership_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL
);


--
-- Name: option_groups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.option_groups (
    display_order integer NOT NULL,
    maximum_selections integer NOT NULL,
    minimum_selections integer NOT NULL,
    required boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    product_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(120) NOT NULL
);


--
-- Name: order_item_options; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_item_options (
    price_adjustment_snapshot numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    order_item_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    product_option_id uuid,
    tenant_id uuid NOT NULL,
    option_name_snapshot character varying(120) NOT NULL
);


--
-- Name: order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_items (
    line_total numeric(19,2) NOT NULL,
    quantity integer NOT NULL,
    unit_price_snapshot numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    product_id uuid,
    sales_order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_name_snapshot character varying(160) NOT NULL,
    note character varying(500)
);


--
-- Name: orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders (
    currency_code character varying(3) NOT NULL,
    subtotal_amount numeric(19,2) NOT NULL,
    total_amount numeric(19,2) NOT NULL,
    cancelled_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    handed_over_at timestamp(6) with time zone,
    ready_at timestamp(6) with time zone,
    received_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    created_by_membership_id uuid NOT NULL,
    customer_id uuid,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    source character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    customer_phone_snapshot character varying(40),
    order_number character varying(50) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    customer_name_snapshot character varying(160) NOT NULL,
    cancellation_reason text
);


--
-- Name: outbox_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_messages (
    attempts integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    occurred_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    aggregate_id uuid NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    aggregate_type character varying(80) NOT NULL,
    event_type character varying(100) NOT NULL,
    deduplication_key character varying(200) NOT NULL,
    payload jsonb NOT NULL
);


--
-- Name: outlet_laundry_service_prices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outlet_laundry_service_prices (
    currency_code character varying(3) NOT NULL,
    unit_price numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    effective_from timestamp(6) with time zone NOT NULL,
    effective_until timestamp(6) with time zone,
    id uuid NOT NULL,
    laundry_service_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL
);


--
-- Name: outlets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outlets (
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    phone_number character varying(32),
    time_zone character varying(64) NOT NULL,
    name character varying(120) NOT NULL,
    address character varying(255)
);


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    amount_paid numeric(19,2) NOT NULL,
    change_amount numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    paid_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    received_by_staff_user_id uuid NOT NULL,
    sales_order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    method character varying(32) NOT NULL,
    receipt_number character varying(32) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    reference character varying(128),
    CONSTRAINT payments_method_check CHECK (((method)::text = ANY ((ARRAY['CASH'::character varying, 'MANUAL_NON_CASH'::character varying, 'MOCK_GATEWAY'::character varying])::text[])))
);


--
-- Name: pickup_delivery_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pickup_delivery_jobs (
    currency_code character varying(3) NOT NULL,
    fee_amount_snapshot numeric(19,2) NOT NULL,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    scheduled_from timestamp(6) with time zone,
    scheduled_until timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    job_type character varying(12) NOT NULL,
    assigned_membership_id uuid,
    customer_id uuid NOT NULL,
    id uuid NOT NULL,
    order_id uuid,
    outlet_id uuid NOT NULL,
    status character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    recipient_phone_snapshot character varying(40) NOT NULL,
    recipient_name_snapshot character varying(160) NOT NULL,
    address_snapshot text NOT NULL
);


--
-- Name: platform_invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_invoices (
    amount_due numeric(19,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    due_at timestamp(6) with time zone,
    issued_at timestamp(6) with time zone NOT NULL,
    paid_at timestamp(6) with time zone,
    id uuid NOT NULL,
    status character varying(16) NOT NULL,
    subscription_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    invoice_number character varying(50) NOT NULL
);


--
-- Name: platform_payment_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_payment_attempts (
    amount numeric(19,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    platform_invoice_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    provider_code character varying(50) NOT NULL,
    request_fingerprint character varying(64) NOT NULL,
    provider_account_ref character varying(120) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    provider_payment_ref character varying(160)
);


--
-- Name: product_options; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_options (
    available boolean NOT NULL,
    display_order integer NOT NULL,
    price_adjustment numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    option_group_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(120) NOT NULL
);


--
-- Name: products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.products (
    archived boolean NOT NULL,
    available boolean NOT NULL,
    display_order integer NOT NULL,
    price numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    category_id uuid NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name character varying(160) NOT NULL,
    image_url character varying(512),
    description character varying(1000)
);


--
-- Name: sales_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_orders (
    discount_amount numeric(19,2) NOT NULL,
    service_charge_amount numeric(19,2) NOT NULL,
    service_charge_rate_snapshot numeric(5,2) NOT NULL,
    subtotal numeric(19,2) NOT NULL,
    tax_amount numeric(19,2) NOT NULL,
    tax_rate_snapshot numeric(5,2) NOT NULL,
    total numeric(19,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    public_status_token_expires_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    dining_table_id uuid,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    payment_status character varying(24) NOT NULL,
    service_type character varying(24) NOT NULL,
    source character varying(24) NOT NULL,
    status character varying(24) NOT NULL,
    order_number character varying(32) NOT NULL,
    public_status_token_digest character varying(64),
    request_fingerprint character varying(64),
    customer_label character varying(80),
    idempotency_key character varying(128) NOT NULL,
    cancellation_reason character varying(500),
    CONSTRAINT sales_orders_payment_status_check CHECK (((payment_status)::text = ANY ((ARRAY['UNPAID'::character varying, 'PAID'::character varying, 'REFUNDED'::character varying])::text[]))),
    CONSTRAINT sales_orders_service_type_check CHECK (((service_type)::text = ANY ((ARRAY['DINE_IN'::character varying, 'TAKEAWAY'::character varying])::text[]))),
    CONSTRAINT sales_orders_source_check CHECK (((source)::text = ANY ((ARRAY['QR'::character varying, 'POS'::character varying])::text[]))),
    CONSTRAINT sales_orders_status_check CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'CONFIRMED'::character varying, 'PREPARING'::character varying, 'READY'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: staff_user_outlet_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.staff_user_outlet_access (
    created_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    tenant_id uuid NOT NULL
);


--
-- Name: staff_users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.staff_users (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    status character varying(16) NOT NULL,
    onboarding_intent character varying(20),
    password_hash character varying(100) NOT NULL,
    full_name character varying(160) NOT NULL,
    email character varying(320) NOT NULL,
    CONSTRAINT staff_users_onboarding_intent_check CHECK (((onboarding_intent)::text = ANY ((ARRAY['OWNER'::character varying, 'CASHIER_ONLY'::character varying])::text[])))
);


--
-- Name: subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscriptions (
    created_at timestamp(6) with time zone NOT NULL,
    ends_at timestamp(6) with time zone,
    starts_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    plan_code character varying(40) NOT NULL
);


--
-- Name: table_qr_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.table_qr_tokens (
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    dining_table_id uuid,
    id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    qr_type character varying(16) NOT NULL,
    tenant_id uuid NOT NULL,
    token character varying(128) NOT NULL
);


--
-- Name: tenant_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_memberships (
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role character varying(32) NOT NULL,
    CONSTRAINT tenant_memberships_role_check CHECK (((role)::text = ANY ((ARRAY['SUPERADMIN'::character varying, 'OWNERMERCHANT'::character varying, 'CASHIER'::character varying])::text[])))
);


--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenants (
    currency character varying(3) NOT NULL,
    service_charge_rate numeric(5,2) NOT NULL,
    tax_rate numeric(5,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    profile_completed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    business_type_id uuid,
    created_by_user_id uuid,
    id uuid NOT NULL,
    status character varying(20) NOT NULL,
    phone_number character varying(32),
    onboarding_request_fingerprint character varying(64),
    time_zone character varying(64) NOT NULL,
    name character varying(120) NOT NULL,
    onboarding_idempotency_key character varying(128),
    logo_url character varying(512),
    address character varying(255)
);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: auth_refresh_tokens auth_refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_refresh_tokens
    ADD CONSTRAINT auth_refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: auth_refresh_tokens auth_refresh_tokens_token_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_refresh_tokens
    ADD CONSTRAINT auth_refresh_tokens_token_digest_key UNIQUE (token_digest);


--
-- Name: auth_sessions auth_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_sessions
    ADD CONSTRAINT auth_sessions_pkey PRIMARY KEY (id);


--
-- Name: business_types business_types_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.business_types
    ADD CONSTRAINT business_types_code_key UNIQUE (code);


--
-- Name: business_types business_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.business_types
    ADD CONSTRAINT business_types_pkey PRIMARY KEY (id);


--
-- Name: cashier_catalog_items cashier_catalog_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_catalog_items
    ADD CONSTRAINT cashier_catalog_items_pkey PRIMARY KEY (id);


--
-- Name: cashier_catalog_items cashier_catalog_items_tenant_id_outlet_id_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_catalog_items
    ADD CONSTRAINT cashier_catalog_items_tenant_id_outlet_id_name_key UNIQUE (tenant_id, outlet_id, name);


--
-- Name: cashier_invitations cashier_invitations_code_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_invitations
    ADD CONSTRAINT cashier_invitations_code_digest_key UNIQUE (code_digest);


--
-- Name: cashier_invitations cashier_invitations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_invitations
    ADD CONSTRAINT cashier_invitations_pkey PRIMARY KEY (id);


--
-- Name: cashier_sale_items cashier_sale_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sale_items
    ADD CONSTRAINT cashier_sale_items_pkey PRIMARY KEY (id);


--
-- Name: cashier_sales cashier_sales_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sales
    ADD CONSTRAINT cashier_sales_pkey PRIMARY KEY (id);


--
-- Name: cashier_sales cashier_sales_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sales
    ADD CONSTRAINT cashier_sales_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: cashier_sales cashier_sales_tenant_id_outlet_id_sale_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sales
    ADD CONSTRAINT cashier_sales_tenant_id_outlet_id_sale_number_key UNIQUE (tenant_id, outlet_id, sale_number);


--
-- Name: categories categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_pkey PRIMARY KEY (id);


--
-- Name: customer_addresses customer_addresses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_addresses
    ADD CONSTRAINT customer_addresses_pkey PRIMARY KEY (id);


--
-- Name: customer_invoice_lines customer_invoice_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoice_lines
    ADD CONSTRAINT customer_invoice_lines_pkey PRIMARY KEY (id);


--
-- Name: customer_invoices customer_invoices_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT customer_invoices_order_id_key UNIQUE (order_id);


--
-- Name: customer_invoices customer_invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT customer_invoices_pkey PRIMARY KEY (id);


--
-- Name: customer_invoices customer_invoices_tenant_id_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT customer_invoices_tenant_id_order_id_key UNIQUE (tenant_id, order_id);


--
-- Name: customer_invoices customer_invoices_tenant_id_outlet_id_invoice_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT customer_invoices_tenant_id_outlet_id_invoice_number_key UNIQUE (tenant_id, outlet_id, invoice_number);


--
-- Name: customer_payments customer_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_payments
    ADD CONSTRAINT customer_payments_pkey PRIMARY KEY (id);


--
-- Name: customer_payments customer_payments_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_payments
    ADD CONSTRAINT customer_payments_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);


--
-- Name: dining_tables dining_tables_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dining_tables
    ADD CONSTRAINT dining_tables_pkey PRIMARY KEY (id);


--
-- Name: discounts discounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discounts
    ADD CONSTRAINT discounts_pkey PRIMARY KEY (id);


--
-- Name: discounts discounts_sales_order_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discounts
    ADD CONSTRAINT discounts_sales_order_id_key UNIQUE (sales_order_id);


--
-- Name: fnb_gateway_attempts fnb_gateway_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT fnb_gateway_attempts_pkey PRIMARY KEY (id);


--
-- Name: fnb_order_status_history fnb_order_status_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_order_status_history
    ADD CONSTRAINT fnb_order_status_history_pkey PRIMARY KEY (id);


--
-- Name: gateway_events gateway_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gateway_events
    ADD CONSTRAINT gateway_events_pkey PRIMARY KEY (id);


--
-- Name: gateway_events gateway_events_provider_code_provider_account_ref_provider__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.gateway_events
    ADD CONSTRAINT gateway_events_provider_code_provider_account_ref_provider__key UNIQUE (provider_code, provider_account_ref, provider_event_id);


--
-- Name: inventory_balances inventory_balances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_balances
    ADD CONSTRAINT inventory_balances_pkey PRIMARY KEY (id);


--
-- Name: inventory_balances inventory_balances_tenant_id_outlet_id_inventory_item_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_balances
    ADD CONSTRAINT inventory_balances_tenant_id_outlet_id_inventory_item_id_key UNIQUE (tenant_id, outlet_id, inventory_item_id);


--
-- Name: inventory_items inventory_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT inventory_items_pkey PRIMARY KEY (id);


--
-- Name: inventory_movements inventory_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT inventory_movements_pkey PRIMARY KEY (id);


--
-- Name: inventory_movements inventory_movements_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT inventory_movements_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: laundry_order_items laundry_order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_order_items
    ADD CONSTRAINT laundry_order_items_pkey PRIMARY KEY (id);


--
-- Name: laundry_order_status_history laundry_order_status_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_order_status_history
    ADD CONSTRAINT laundry_order_status_history_pkey PRIMARY KEY (id);


--
-- Name: laundry_orders laundry_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_orders
    ADD CONSTRAINT laundry_orders_pkey PRIMARY KEY (id);


--
-- Name: laundry_service_categories laundry_service_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_service_categories
    ADD CONSTRAINT laundry_service_categories_pkey PRIMARY KEY (id);


--
-- Name: laundry_service_categories laundry_service_categories_tenant_id_outlet_id_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_service_categories
    ADD CONSTRAINT laundry_service_categories_tenant_id_outlet_id_name_key UNIQUE (tenant_id, outlet_id, name);


--
-- Name: laundry_services laundry_services_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_services
    ADD CONSTRAINT laundry_services_pkey PRIMARY KEY (id);


--
-- Name: laundry_services laundry_services_tenant_id_outlet_id_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_services
    ADD CONSTRAINT laundry_services_tenant_id_outlet_id_name_key UNIQUE (tenant_id, outlet_id, name);


--
-- Name: membership_outlet_access membership_outlet_access_membership_id_outlet_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_outlet_access
    ADD CONSTRAINT membership_outlet_access_membership_id_outlet_id_key UNIQUE (membership_id, outlet_id);


--
-- Name: membership_outlet_access membership_outlet_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_outlet_access
    ADD CONSTRAINT membership_outlet_access_pkey PRIMARY KEY (id);


--
-- Name: option_groups option_groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_groups
    ADD CONSTRAINT option_groups_pkey PRIMARY KEY (id);


--
-- Name: order_item_options order_item_options_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_item_options
    ADD CONSTRAINT order_item_options_pkey PRIMARY KEY (id);


--
-- Name: order_items order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: orders orders_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: orders orders_tenant_id_outlet_id_order_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_tenant_id_outlet_id_order_number_key UNIQUE (tenant_id, outlet_id, order_number);


--
-- Name: outbox_messages outbox_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_messages
    ADD CONSTRAINT outbox_messages_pkey PRIMARY KEY (id);


--
-- Name: outbox_messages outbox_messages_tenant_id_deduplication_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_messages
    ADD CONSTRAINT outbox_messages_tenant_id_deduplication_key_key UNIQUE (tenant_id, deduplication_key);


--
-- Name: outlet_laundry_service_prices outlet_laundry_service_prices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlet_laundry_service_prices
    ADD CONSTRAINT outlet_laundry_service_prices_pkey PRIMARY KEY (id);


--
-- Name: outlet_laundry_service_prices outlet_laundry_service_prices_tenant_id_outlet_id_laundry_s_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlet_laundry_service_prices
    ADD CONSTRAINT outlet_laundry_service_prices_tenant_id_outlet_id_laundry_s_key UNIQUE (tenant_id, outlet_id, laundry_service_id, effective_from);


--
-- Name: outlets outlets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlets
    ADD CONSTRAINT outlets_pkey PRIMARY KEY (id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: pickup_delivery_jobs pickup_delivery_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pickup_delivery_jobs
    ADD CONSTRAINT pickup_delivery_jobs_pkey PRIMARY KEY (id);


--
-- Name: platform_invoices platform_invoices_invoice_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_invoices
    ADD CONSTRAINT platform_invoices_invoice_number_key UNIQUE (invoice_number);


--
-- Name: platform_invoices platform_invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_invoices
    ADD CONSTRAINT platform_invoices_pkey PRIMARY KEY (id);


--
-- Name: platform_payment_attempts platform_payment_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_payment_attempts
    ADD CONSTRAINT platform_payment_attempts_pkey PRIMARY KEY (id);


--
-- Name: platform_payment_attempts platform_payment_attempts_tenant_id_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_payment_attempts
    ADD CONSTRAINT platform_payment_attempts_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: product_options product_options_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_options
    ADD CONSTRAINT product_options_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: sales_orders sales_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT sales_orders_pkey PRIMARY KEY (id);


--
-- Name: sales_orders sales_orders_public_status_token_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT sales_orders_public_status_token_digest_key UNIQUE (public_status_token_digest);


--
-- Name: staff_user_outlet_access staff_user_outlet_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_user_outlet_access
    ADD CONSTRAINT staff_user_outlet_access_pkey PRIMARY KEY (id);


--
-- Name: staff_users staff_users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_users
    ADD CONSTRAINT staff_users_email_key UNIQUE (email);


--
-- Name: staff_users staff_users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_users
    ADD CONSTRAINT staff_users_pkey PRIMARY KEY (id);


--
-- Name: subscriptions subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);


--
-- Name: table_qr_tokens table_qr_tokens_dining_table_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.table_qr_tokens
    ADD CONSTRAINT table_qr_tokens_dining_table_id_key UNIQUE (dining_table_id);


--
-- Name: table_qr_tokens table_qr_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.table_qr_tokens
    ADD CONSTRAINT table_qr_tokens_pkey PRIMARY KEY (id);


--
-- Name: tenant_memberships tenant_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT tenant_memberships_pkey PRIMARY KEY (id);


--
-- Name: tenant_memberships tenant_memberships_tenant_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT tenant_memberships_tenant_id_user_id_key UNIQUE (tenant_id, user_id);


--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);


--
-- Name: categories uk_categories_outlet_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT uk_categories_outlet_name UNIQUE (outlet_id, name);


--
-- Name: dining_tables uk_dining_tables_outlet_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dining_tables
    ADD CONSTRAINT uk_dining_tables_outlet_name UNIQUE (outlet_id, name);


--
-- Name: fnb_gateway_attempts uk_fnb_gateway_attempt_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT uk_fnb_gateway_attempt_key UNIQUE (tenant_id, idempotency_key);


--
-- Name: outlets uk_outlets_tenant_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlets
    ADD CONSTRAINT uk_outlets_tenant_name UNIQUE (tenant_id, name);


--
-- Name: payments uk_payments_outlet_receipt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uk_payments_outlet_receipt UNIQUE (outlet_id, receipt_number);


--
-- Name: payments uk_payments_sales_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uk_payments_sales_order UNIQUE (sales_order_id);


--
-- Name: payments uk_payments_tenant_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uk_payments_tenant_idempotency UNIQUE (tenant_id, idempotency_key);


--
-- Name: sales_orders uk_sales_orders_outlet_order_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT uk_sales_orders_outlet_order_number UNIQUE (outlet_id, order_number);


--
-- Name: sales_orders uk_sales_orders_tenant_idempotency; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT uk_sales_orders_tenant_idempotency UNIQUE (tenant_id, idempotency_key);


--
-- Name: staff_user_outlet_access uk_staff_user_outlet_access; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_user_outlet_access
    ADD CONSTRAINT uk_staff_user_outlet_access UNIQUE (staff_user_id, outlet_id);


--
-- Name: table_qr_tokens uk_table_qr_tokens_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.table_qr_tokens
    ADD CONSTRAINT uk_table_qr_tokens_token UNIQUE (token);


--
-- Name: payments fk13eta12h6s9xy93m97xta7f0h; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk13eta12h6s9xy93m97xta7f0h FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id);


--
-- Name: customer_invoice_lines fk18ue4hri3w0y01piuainegtd9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoice_lines
    ADD CONSTRAINT fk18ue4hri3w0y01piuainegtd9 FOREIGN KEY (customer_invoice_id) REFERENCES public.customer_invoices(id);


--
-- Name: discounts fk1x7pb3k956ijrxboaypaksmdo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discounts
    ADD CONSTRAINT fk1x7pb3k956ijrxboaypaksmdo FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: laundry_service_categories fk1xrtbq8v8e7o1cqe8i0jgcylw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_service_categories
    ADD CONSTRAINT fk1xrtbq8v8e7o1cqe8i0jgcylw FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: order_items fk2uqu14iv77b72p1nnvqt9llqw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT fk2uqu14iv77b72p1nnvqt9llqw FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: fnb_gateway_attempts fk2v7q0eiai5l42ftmltny4y0i2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT fk2v7q0eiai5l42ftmltny4y0i2 FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id);


--
-- Name: table_qr_tokens fk3a9a8dbxn4k97tow8ekkyvagx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.table_qr_tokens
    ADD CONSTRAINT fk3a9a8dbxn4k97tow8ekkyvagx FOREIGN KEY (dining_table_id) REFERENCES public.dining_tables(id);


--
-- Name: outlet_laundry_service_prices fk3j9ugvcq5ywbojcm9p49ncb4f; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlet_laundry_service_prices
    ADD CONSTRAINT fk3j9ugvcq5ywbojcm9p49ncb4f FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: membership_outlet_access fk42fib2ryml6phrangk1ihbp3f; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_outlet_access
    ADD CONSTRAINT fk42fib2ryml6phrangk1ihbp3f FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: orders fk45pihpalbn6gyf7x3l9hbybw0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fk45pihpalbn6gyf7x3l9hbybw0 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: payments fk47pgal76pui0no18pwwcdd64m; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk47pgal76pui0no18pwwcdd64m FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: discounts fk4ge5ur4na80s7fratbl69swdo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discounts
    ADD CONSTRAINT fk4ge5ur4na80s7fratbl69swdo FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: tenants fk4tgetql830a4cutcsgm7e9pap; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT fk4tgetql830a4cutcsgm7e9pap FOREIGN KEY (created_by_user_id) REFERENCES public.staff_users(id);


--
-- Name: inventory_items fk5e9ulbutuklbr0en55hiedfpq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT fk5e9ulbutuklbr0en55hiedfpq FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: inventory_movements fk5g2urco9ofj37nh4x8v01fdxv; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_movements
    ADD CONSTRAINT fk5g2urco9ofj37nh4x8v01fdxv FOREIGN KEY (inventory_item_id) REFERENCES public.inventory_items(id);


--
-- Name: table_qr_tokens fk5ixf5yvqgxvls7y6otae2q5n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.table_qr_tokens
    ADD CONSTRAINT fk5ixf5yvqgxvls7y6otae2q5n FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: membership_outlet_access fk5l93981bisnvn4yjsmrri2hc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_outlet_access
    ADD CONSTRAINT fk5l93981bisnvn4yjsmrri2hc FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: order_item_options fk5p0ngwndjrenmlppktkfywjsd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_item_options
    ADD CONSTRAINT fk5p0ngwndjrenmlppktkfywjsd FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: customer_invoices fk5v835cuagtb27fpk8gfqaiff7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT fk5v835cuagtb27fpk8gfqaiff7 FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: cashier_sale_items fk68elky475xneg8pjulgappf9j; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sale_items
    ADD CONSTRAINT fk68elky475xneg8pjulgappf9j FOREIGN KEY (catalog_item_id) REFERENCES public.cashier_catalog_items(id);


--
-- Name: subscriptions fk6cld8vfsju3r2hur8pytoanbj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT fk6cld8vfsju3r2hur8pytoanbj FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: membership_outlet_access fk7c5vfu84h9tu7ivspbfw4vhh1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_outlet_access
    ADD CONSTRAINT fk7c5vfu84h9tu7ivspbfw4vhh1 FOREIGN KEY (membership_id) REFERENCES public.tenant_memberships(id);


--
-- Name: fnb_gateway_attempts fk7p4k9ifl7niyl316qa8kdnwv5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT fk7p4k9ifl7niyl316qa8kdnwv5 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: cashier_invitations fk7q856678nt4wtgf3lrivxtxs3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_invitations
    ADD CONSTRAINT fk7q856678nt4wtgf3lrivxtxs3 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: order_items fk84igc3jpcbslfgoefmqpy2yc7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT fk84igc3jpcbslfgoefmqpy2yc7 FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id);


--
-- Name: platform_invoices fk8pcbpdid09m017a51br0jrww0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_invoices
    ADD CONSTRAINT fk8pcbpdid09m017a51br0jrww0 FOREIGN KEY (subscription_id) REFERENCES public.subscriptions(id);


--
-- Name: outlets fk8smpggeetmfv3titotpn0sb88; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlets
    ADD CONSTRAINT fk8smpggeetmfv3titotpn0sb88 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: orders fk91hpo6ynfiex02lrim71wr2d0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fk91hpo6ynfiex02lrim71wr2d0 FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: payments fk91wafdfakn9yu275jw6hwnucg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk91wafdfakn9yu275jw6hwnucg FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: dining_tables fk9nmjmodhxwvq3hi8pgil71bko; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dining_tables
    ADD CONSTRAINT fk9nmjmodhxwvq3hi8pgil71bko FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: auth_sessions fk9rsiq0to6cmmm37rvdo8fglvh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_sessions
    ADD CONSTRAINT fk9rsiq0to6cmmm37rvdo8fglvh FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: laundry_order_items fk9tgjt7xyetlpp8elowckpw7e2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_order_items
    ADD CONSTRAINT fk9tgjt7xyetlpp8elowckpw7e2 FOREIGN KEY (laundry_order_id) REFERENCES public.laundry_orders(id);


--
-- Name: customer_payments fkaabxjnh3l2uhqahjexscnl66x; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_payments
    ADD CONSTRAINT fkaabxjnh3l2uhqahjexscnl66x FOREIGN KEY (customer_invoice_id) REFERENCES public.customer_invoices(id);


--
-- Name: staff_user_outlet_access fkao4wvf5jvc5kqyohbd3ph4fht; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_user_outlet_access
    ADD CONSTRAINT fkao4wvf5jvc5kqyohbd3ph4fht FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: laundry_services fkb2ea6wdkb6111phwn1q31ddxq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_services
    ADD CONSTRAINT fkb2ea6wdkb6111phwn1q31ddxq FOREIGN KEY (category_id) REFERENCES public.laundry_service_categories(id);


--
-- Name: products fkboh9ahdgv0bpqp7bbkqhf9trh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT fkboh9ahdgv0bpqp7bbkqhf9trh FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: laundry_orders fkby7wefn6nnjj7ca9ggxji8be7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_orders
    ADD CONSTRAINT fkby7wefn6nnjj7ca9ggxji8be7 FOREIGN KEY (id) REFERENCES public.orders(id);


--
-- Name: cashier_invitations fkcb84r9rmt08suwc9fkgsbal9v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_invitations
    ADD CONSTRAINT fkcb84r9rmt08suwc9fkgsbal9v FOREIGN KEY (invited_by_membership_id) REFERENCES public.tenant_memberships(id);


--
-- Name: staff_user_outlet_access fkcg5mg53ekgu8cw97atixejdu7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_user_outlet_access
    ADD CONSTRAINT fkcg5mg53ekgu8cw97atixejdu7 FOREIGN KEY (staff_user_id) REFERENCES public.staff_users(id);


--
-- Name: cashier_sale_items fkcy9xx8q9uxm6muh5j2io826pu; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sale_items
    ADD CONSTRAINT fkcy9xx8q9uxm6muh5j2io826pu FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: discounts fkdjfovlntw7rn55uxogv7ldirr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discounts
    ADD CONSTRAINT fkdjfovlntw7rn55uxogv7ldirr FOREIGN KEY (granted_by_staff_user_id) REFERENCES public.staff_users(id);


--
-- Name: tenants fkdolad4il51imip6ufd0lica5x; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT fkdolad4il51imip6ufd0lica5x FOREIGN KEY (business_type_id) REFERENCES public.business_types(id);


--
-- Name: cashier_sales fkdoo96okdq3nqwmhedo3jy0fd3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sales
    ADD CONSTRAINT fkdoo96okdq3nqwmhedo3jy0fd3 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: tenant_memberships fkdwd7dgwbe15d4xk27cab013do; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT fkdwd7dgwbe15d4xk27cab013do FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: cashier_sales fkeijdagjn01htkkhn3tj1j3cyb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sales
    ADD CONSTRAINT fkeijdagjn01htkkhn3tj1j3cyb FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: customer_invoices fkep33apjif2rd88l0mwmnx9oh0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT fkep33apjif2rd88l0mwmnx9oh0 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: sales_orders fkeqyrndfmakgfsxa82nrc1vraw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT fkeqyrndfmakgfsxa82nrc1vraw FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: cashier_invitations fkeu6x7r0odbeyxqrogbsdmfc9i; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_invitations
    ADD CONSTRAINT fkeu6x7r0odbeyxqrogbsdmfc9i FOREIGN KEY (claimed_by_user_id) REFERENCES public.staff_users(id);


--
-- Name: product_options fkf62rjtefu9dg0juklh5sn7f7n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_options
    ADD CONSTRAINT fkf62rjtefu9dg0juklh5sn7f7n FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: auth_sessions fkfhas6cpbxwe832vb6eb5nm7j4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_sessions
    ADD CONSTRAINT fkfhas6cpbxwe832vb6eb5nm7j4 FOREIGN KEY (user_id) REFERENCES public.staff_users(id);


--
-- Name: tenant_memberships fkfk52f79v7obfifhmuhi1f8mad; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT fkfk52f79v7obfifhmuhi1f8mad FOREIGN KEY (user_id) REFERENCES public.staff_users(id);


--
-- Name: order_item_options fkfp1qv80akkf0uvn18st661uuj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_item_options
    ADD CONSTRAINT fkfp1qv80akkf0uvn18st661uuj FOREIGN KEY (product_option_id) REFERENCES public.product_options(id);


--
-- Name: table_qr_tokens fkfp9wpag9swry4gelnk9fsoev7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.table_qr_tokens
    ADD CONSTRAINT fkfp9wpag9swry4gelnk9fsoev7 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: order_items fkfxrmjdvu3mqxa0g4yanufokge; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT fkfxrmjdvu3mqxa0g4yanufokge FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: platform_payment_attempts fkg59mrd8fymp331to2wqugr67c; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_payment_attempts
    ADD CONSTRAINT fkg59mrd8fymp331to2wqugr67c FOREIGN KEY (platform_invoice_id) REFERENCES public.platform_invoices(id);


--
-- Name: sales_orders fkg6n2136aj7ptei54uuxs24vgr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT fkg6n2136aj7ptei54uuxs24vgr FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: customer_invoices fkgik7mb6habfxb6vixyyo6mqtc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_invoices
    ADD CONSTRAINT fkgik7mb6habfxb6vixyyo6mqtc FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: option_groups fkgitc39v93fp481wvy6dkie0rf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_groups
    ADD CONSTRAINT fkgitc39v93fp481wvy6dkie0rf FOREIGN KEY (product_id) REFERENCES public.products(id);


--
-- Name: laundry_services fkgpqfmwri0s2bm0iycbayq9678; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_services
    ADD CONSTRAINT fkgpqfmwri0s2bm0iycbayq9678 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: product_options fkhwcxpo37hs2ac9k1st88k3hf1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_options
    ADD CONSTRAINT fkhwcxpo37hs2ac9k1st88k3hf1 FOREIGN KEY (option_group_id) REFERENCES public.option_groups(id);


--
-- Name: customer_addresses fkie4m3if6oqajloghosrpqa13q; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_addresses
    ADD CONSTRAINT fkie4m3if6oqajloghosrpqa13q FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: staff_user_outlet_access fkimahsdvfn31gu7cq9xsj6nu0p; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_user_outlet_access
    ADD CONSTRAINT fkimahsdvfn31gu7cq9xsj6nu0p FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: outlet_laundry_service_prices fkirc6wvxf2f7xqa29igf6e34im; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlet_laundry_service_prices
    ADD CONSTRAINT fkirc6wvxf2f7xqa29igf6e34im FOREIGN KEY (laundry_service_id) REFERENCES public.laundry_services(id);


--
-- Name: cashier_sale_items fkitqkfvc9nnjk1pan72bxe25q3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sale_items
    ADD CONSTRAINT fkitqkfvc9nnjk1pan72bxe25q3 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: fnb_gateway_attempts fkiu43io5do6vi8povac8vtbpo7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT fkiu43io5do6vi8povac8vtbpo7 FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: order_item_options fkj86fk6o730q8irsbpu5agy3el; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_item_options
    ADD CONSTRAINT fkj86fk6o730q8irsbpu5agy3el FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: cashier_catalog_items fkk6xti6o3eo0mmjas8ra7x2wrk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_catalog_items
    ADD CONSTRAINT fkk6xti6o3eo0mmjas8ra7x2wrk FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: inventory_balances fkkjmmo5rxo9lyq1ntmeu0b96p1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_balances
    ADD CONSTRAINT fkkjmmo5rxo9lyq1ntmeu0b96p1 FOREIGN KEY (inventory_item_id) REFERENCES public.inventory_items(id);


--
-- Name: inventory_items fkl12iqikwkd4ret8bsvm350cod; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inventory_items
    ADD CONSTRAINT fkl12iqikwkd4ret8bsvm350cod FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: option_groups fkldu126e1tcw19mwdm885ubr3n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_groups
    ADD CONSTRAINT fkldu126e1tcw19mwdm885ubr3n FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: audit_logs fklllmajnn2iurk5riyomkm3u4a; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT fklllmajnn2iurk5riyomkm3u4a FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: cashier_catalog_items fkmblai84dwg7fnf1onij824qq2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_catalog_items
    ADD CONSTRAINT fkmblai84dwg7fnf1onij824qq2 FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: sales_orders fkmebvqgi38ia2qo6as3a2nqxtv; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_orders
    ADD CONSTRAINT fkmebvqgi38ia2qo6as3a2nqxtv FOREIGN KEY (dining_table_id) REFERENCES public.dining_tables(id);


--
-- Name: products fkn8rwil6da3e23e0g04r5cw0mf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT fkn8rwil6da3e23e0g04r5cw0mf FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: discounts fkn8uew4x6d8rgnwgl91k7ny6ha; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discounts
    ADD CONSTRAINT fkn8uew4x6d8rgnwgl91k7ny6ha FOREIGN KEY (sales_order_id) REFERENCES public.sales_orders(id);


--
-- Name: product_options fkndpb6x4ww77a4obdehu0rog2s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_options
    ADD CONSTRAINT fkndpb6x4ww77a4obdehu0rog2s FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: outlet_laundry_service_prices fknhng1l4pqdr1srx2khhjq0suq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outlet_laundry_service_prices
    ADD CONSTRAINT fknhng1l4pqdr1srx2khhjq0suq FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: fnb_gateway_attempts fko5ji1u7s2nn5jsglxjgwkcvqh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT fko5ji1u7s2nn5jsglxjgwkcvqh FOREIGN KEY (resolved_by_staff_user_id) REFERENCES public.staff_users(id);


--
-- Name: auth_refresh_tokens fko6vv8xy22ehotn1jh9hs9opd0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_refresh_tokens
    ADD CONSTRAINT fko6vv8xy22ehotn1jh9hs9opd0 FOREIGN KEY (session_id) REFERENCES public.auth_sessions(id);


--
-- Name: order_items fkocimc7dtr037rh4ls4l95nlfi; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT fkocimc7dtr037rh4ls4l95nlfi FOREIGN KEY (product_id) REFERENCES public.products(id);


--
-- Name: cashier_sale_items fkoe2cac7l78y6tlxnbu9peq86u; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_sale_items
    ADD CONSTRAINT fkoe2cac7l78y6tlxnbu9peq86u FOREIGN KEY (sale_id) REFERENCES public.cashier_sales(id);


--
-- Name: products fkog2rp4qthbtt2lfyhfo32lsw9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT fkog2rp4qthbtt2lfyhfo32lsw9 FOREIGN KEY (category_id) REFERENCES public.categories(id);


--
-- Name: laundry_service_categories fkol9lh5su34gqdyljub6qulgdg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_service_categories
    ADD CONSTRAINT fkol9lh5su34gqdyljub6qulgdg FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: order_item_options fkoxx4p9r1asmwop4wuynr6t0g6; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_item_options
    ADD CONSTRAINT fkoxx4p9r1asmwop4wuynr6t0g6 FOREIGN KEY (order_item_id) REFERENCES public.order_items(id);


--
-- Name: dining_tables fkp2c818k62vwiokipic9m6nygh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dining_tables
    ADD CONSTRAINT fkp2c818k62vwiokipic9m6nygh FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: customers fkpd6k5i4wnbhgk8fwug35v2x5t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customers
    ADD CONSTRAINT fkpd6k5i4wnbhgk8fwug35v2x5t FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: laundry_services fkpf3r8w84nn2lraknxeh0u14wf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_services
    ADD CONSTRAINT fkpf3r8w84nn2lraknxeh0u14wf FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: orders fkpxtb8awmi0dk6smoh2vp1litg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fkpxtb8awmi0dk6smoh2vp1litg FOREIGN KEY (customer_id) REFERENCES public.customers(id);


--
-- Name: categories fkq4yscelicj6kitwpsw9ycs2ms; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT fkq4yscelicj6kitwpsw9ycs2ms FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: platform_invoices fkq79uu23fwdpnjyml4gbbdmfnt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_invoices
    ADD CONSTRAINT fkq79uu23fwdpnjyml4gbbdmfnt FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: audit_logs fkqquet9030xtkk7jyvqwuy1byu; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT fkqquet9030xtkk7jyvqwuy1byu FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: laundry_order_items fkr5uyjc72es2pfqxmedaja5b02; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.laundry_order_items
    ADD CONSTRAINT fkr5uyjc72es2pfqxmedaja5b02 FOREIGN KEY (laundry_service_id) REFERENCES public.laundry_services(id);


--
-- Name: audit_logs fkrm36l39ng9ljrn6uyluam35k8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT fkrm36l39ng9ljrn6uyluam35k8 FOREIGN KEY (actor_staff_user_id) REFERENCES public.staff_users(id);


--
-- Name: customer_addresses fkrvr6wl9gll7u98cda18smugp4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer_addresses
    ADD CONSTRAINT fkrvr6wl9gll7u98cda18smugp4 FOREIGN KEY (customer_id) REFERENCES public.customers(id);


--
-- Name: payments fkshd5pn80wh7trutv9n7a5mw6j; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fkshd5pn80wh7trutv9n7a5mw6j FOREIGN KEY (received_by_staff_user_id) REFERENCES public.staff_users(id);


--
-- Name: categories fkslnulryuuh7tq6tgyusgfk336; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT fkslnulryuuh7tq6tgyusgfk336 FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);


--
-- Name: auth_sessions fksmbdychgorfpr6fn4jsryq36l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_sessions
    ADD CONSTRAINT fksmbdychgorfpr6fn4jsryq36l FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: fnb_gateway_attempts fkt75rg0lrwele1o0yr36iopiem; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fnb_gateway_attempts
    ADD CONSTRAINT fkt75rg0lrwele1o0yr36iopiem FOREIGN KEY (initiated_by_staff_user_id) REFERENCES public.staff_users(id);


--
-- Name: auth_sessions fktaw26gi1kfay0cayvurfv7ui8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_sessions
    ADD CONSTRAINT fktaw26gi1kfay0cayvurfv7ui8 FOREIGN KEY (membership_id) REFERENCES public.tenant_memberships(id);


--
-- Name: option_groups fktdwh6gjdyffhftkhcrph3bw13; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.option_groups
    ADD CONSTRAINT fktdwh6gjdyffhftkhcrph3bw13 FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- Name: cashier_invitations fktestp67f350dqtvjg4hxo2yso; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cashier_invitations
    ADD CONSTRAINT fktestp67f350dqtvjg4hxo2yso FOREIGN KEY (outlet_id) REFERENCES public.outlets(id);


--
-- PostgreSQL database dump complete
--



