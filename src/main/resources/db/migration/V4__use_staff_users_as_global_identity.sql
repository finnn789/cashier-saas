-- Transition identity ownership from users to staff_users without deleting the
-- legacy tables. Existing user IDs and password hashes are copied unchanged so
-- JWT subjects and credential hashes remain valid after the application switch.
--
-- Deployment prerequisite: this migration assumes V1 through V3 have been
-- applied and recorded (or the database has been correctly baselined). Do not
-- enable Flyway against an unbaselined Hibernate-created database.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users legacy_user
        JOIN staff_users staff_user ON LOWER(staff_user.email) = LOWER(legacy_user.email)
    ) THEN
        RAISE EXCEPTION 'Cannot migrate global users: case-insensitive email collision with staff_users exists.';
    END IF;
END $$;

ALTER TABLE staff_users ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE staff_users ADD COLUMN IF NOT EXISTS full_name VARCHAR(160);
ALTER TABLE staff_users ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE staff_users ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE staff_users ALTER COLUMN password_hash TYPE VARCHAR(255);

UPDATE staff_users
SET full_name = COALESCE(full_name, email),
    status = COALESCE(status, CASE WHEN active THEN 'ACTIVE' ELSE 'DISABLED' END);

INSERT INTO staff_users (id, tenant_id, email, password_hash, active, full_name, status, version, created_at, updated_at)
SELECT id,
       NULL,
       email,
       password_hash,
       status <> 'DISABLED',
       full_name,
       status,
       version,
       created_at,
       updated_at
FROM users;

ALTER TABLE staff_users ALTER COLUMN full_name SET NOT NULL;
ALTER TABLE staff_users ALTER COLUMN status SET NOT NULL;
ALTER TABLE staff_users ADD CONSTRAINT ck_staff_users_status
    CHECK (status IN ('PENDING_PROFILE', 'ACTIVE', 'DISABLED'));

-- Membership rows retain their IDs, tenant IDs, roles, and user IDs. The copied
-- staff rows use the same IDs, so only the foreign-key target needs to change.
ALTER TABLE tenant_memberships DROP CONSTRAINT IF EXISTS tenant_memberships_user_id_fkey;
ALTER TABLE tenant_memberships ADD CONSTRAINT fk_tenant_memberships_staff_user
    FOREIGN KEY (user_id) REFERENCES staff_users(id);

-- The legacy users and staff_user_roles tables remain in place for recovery and
-- historical inspection. Runtime identity and authorization use staff_users and
-- tenant_memberships after this migration.
