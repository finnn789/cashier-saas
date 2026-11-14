# TASK-082 D1 report: docs, contracts, Postman

No code changed, nothing run against a database or the app. SQL in the runbook is not executed (checked against V1/V11 and entity logic by reading only).

## Changed files
- `docs/ops/plan-activation.md` (new, Indonesian): operator SQL runbook. Sections: rules (verify payment first, BEGIN/COMMIT, psql variables), how plans work (E1 base row + paid rows, expiry auto-downgrade, data never deleted, E2 cashier access), 1 find tenant by owner email (`staff_users` + `tenant_memberships`), 2 activate PRO/PREMIUM for N months (guard SELECT then INSERT, all NOT NULL columns of `subscriptions` incl. `gen_random_uuid()` and `version = 0`), 3 extend, 4 switch PRO<->PREMIUM, 5 top-up +200 (upsert `ai_usage_periods`, `period_month`/`day_date` in `tenants.time_zone`), 6 check effective plan / trial / usage, troubleshooting.
- `docs/contracts/cashier-service.grpc.schema.json`: plan-gate paragraph in the description; `$defs` and `x-methods` for `ConsumeAiQuestion` and `RecordAiUsage`; `FAILED_PRECONDITION` added to the errors of the five existing RPCs; three examples. CRLF preserved, JSON validated.
- `docs/contracts/cashier-service.grpc.test-messages.json`: new groups `PlanGate`, `ConsumeAiQuestion`, `RecordAiUsage` and four new placeholders. CRLF preserved, JSON validated.
- Postman (live format is the v3 directory `postman/collections/KedaiMbo API/`; the root `KedaiMbo.postman_collection.json` is a stale 12-folder export, untouched):
  - `02 - Membership Plans/List Available Plans.request.yaml` (+ `examples:` pointer) and new example `200 OK` with the three plans and prices (0/0, 59000/590000, 119000/1190000).
  - `04 - Cashier Invitations/Issue Cashier Invitation.request.yaml` (+ pointer) and new `402 Payment Required` example.
  - `16 - F&B Shifts/Refund Order.request.yaml` (+ pointer) and new `402 Payment Required` example. (The owner refund request lives in folder 16.)
  - New files under each folder's `.resources/<request>.resources/examples/`. `postman collection lint "KedaiMbo API"` reports no issues (71 scanned).

## Behaviour documented (from J1-J4 reports and code)
402 body is `{success:false, code:402, message:"Fitur ini tersedia di paket Pro.", timestamp}`; Consume reasons and `warn` rule (used*5 >= limit*4); RecordAiUsage never fails the caller and has no owner/plan check.

## Open questions / notes
1. Docs under `docs/` are mostly English; the runbook is Indonesian as instructed. Say if you want English.
2. Runbook assumes the 14-day trial and 300/40 constants as implemented; a trial is never set manually by an operator.
3. Postman 402 examples use the message for PRO; a Premium-gated REST feature does not exist, so no Premium 402 example.
4. Schema `ConsumeAiQuestionResponse` is documented with proto3 JSON omission (allowed=false is omitted). Stale root Postman JSON not updated by design.
