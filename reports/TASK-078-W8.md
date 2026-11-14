# TASK-078-W8 report

## Changes
1. `src/main/resources/application.properties`: deleted the stale line `# Mock payment gateway (demo/test only); production keeps it off.` and its trailing blank line (one blank line remains before `# gRPC Server`). Nothing else changed.
2. `src/main/resources/db/demo/R__demo_seed.sql`: added, right after the `outlets` insert (before `tenant_memberships`), one comment line plus an `INSERT INTO payment_labels (id, created_at, updated_at, version, tenant_id, outlet_id, name, active, display_order)` for outlet `...0000000000d1` (tenant `...0000000000b1`):
   - QRIS, display_order 1, id `00000000-0000-4000-8000-000000000d11`
   - Transfer, display_order 2, id `00000000-0000-4000-8000-000000000d12`
   - Debit, display_order 3, id `00000000-0000-4000-8000-000000000d13`
   all `active = true`, `version = 0`, ending in `ON CONFLICT DO NOTHING`.

## Idempotency
Matches the file's own style (fixed UUIDs + target-less `ON CONFLICT DO NOTHING`). Target-less conflict handling also absorbs the `uk_payment_labels_outlet_name (outlet_id, lower(name))` unique index, so re-running, or running against a DB where V8 already inserted these names for this outlet, neither fails nor duplicates. The seed creates only one outlet, so one set of labels.

## Not run
No Maven, no SQL executed, no git changes (per instructions/policy).
