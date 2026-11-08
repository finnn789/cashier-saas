-- Staff phone number (digits only, normalized by the application, e.g. 6281234567890).
-- Nullable: rows created before this migration have no phone. No unique constraint:
-- a phone may legitimately belong to staff of several tenants.
ALTER TABLE staff_users ADD COLUMN phone_number varchar(20);
