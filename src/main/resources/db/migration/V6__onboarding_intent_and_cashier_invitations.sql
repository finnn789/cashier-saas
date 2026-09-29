ALTER TABLE staff_users ADD COLUMN onboarding_intent VARCHAR(20);
ALTER TABLE staff_users ADD CONSTRAINT ck_staff_users_onboarding_intent
    CHECK (onboarding_intent IN ('OWNER', 'CASHIER_ONLY'));

UPDATE staff_users user_account SET onboarding_intent = 'OWNER'
WHERE user_account.status = 'PENDING_PROFILE'
  AND EXISTS (SELECT 1 FROM tenant_memberships membership
              WHERE membership.user_id = user_account.id AND membership.role = 'OWNERMERCHANT');

CREATE TABLE cashier_invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    outlet_id UUID NOT NULL,
    invited_email VARCHAR(320) NOT NULL,
    code_digest CHAR(64) NOT NULL UNIQUE,
    invited_by_membership_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    claimed_by_user_id UUID REFERENCES staff_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_cashier_invitation_outlet FOREIGN KEY (tenant_id, outlet_id)
        REFERENCES outlets(tenant_id, id),
    CONSTRAINT fk_cashier_invitation_inviter FOREIGN KEY (tenant_id, invited_by_membership_id)
        REFERENCES tenant_memberships(tenant_id, id),
    CONSTRAINT ck_cashier_invitation_claim CHECK (
        (claimed_at IS NULL AND claimed_by_user_id IS NULL)
        OR (claimed_at IS NOT NULL AND claimed_by_user_id IS NOT NULL))
);
CREATE INDEX ix_cashier_invitations_tenant_outlet
    ON cashier_invitations(tenant_id, outlet_id, created_at DESC);
