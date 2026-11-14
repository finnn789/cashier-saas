# TASK-083 D1 report (docs and Postman)

Status: DONE (not run; read/write docs only, no tests by policy).

## Changed files
- `docs/ops/plan-activation.md`: version line (v2, TASK-083), "Aturan" rule 3 (mentions `extra_outlets`), new section "7. Add-on outlet tambahan" with 7a activate/change N (UPDATE on the active paid row, psql `:extra`), 7b stop (set 0), 7c checks (allowed outlets vs active outlets ranked by `created_at, id`, `dapat_dipakai` flag), principles (FREETIER none, expires with paid row, downgrade never deletes, rank 1 always usable, 402 until deactivate/renew), plus a troubleshooting bullet for the add-on 402 message. Section 4 (change plan) note: copy `extra_outlets` into the new row.
- Postman: the repo has the v3 YAML collection `postman/collections/KedaiMbo API/` (synced by `.github/workflows/postman-sync.yml`) and a stale v2.1 JSON `postman/KedaiMbo.postman_collection.json` (Nov 12, lacks folders 16-20). Updated only the YAML collection; JSON export left untouched.
  - `.resources/definition.yaml`: new collection variable `targetOutletId`.
  - `05 - Outlets/`: `List Merchant Outlets` (GET /api/v1/merchant/outlets), `Create Outlet` (POST, body name/timeZone/address/phoneNumber, afterResponse sets `targetOutletId`), `Update Outlet` (PUT /{targetOutletId}), `Deactivate Outlet` (POST), `Reactivate Outlet` (POST). Examples: List 200, Create 201 and 402 (add-on message), Deactivate 409.
  - `20 - F&B Owner Reports/`: `Consolidated Sales Report` (GET /api/v1/merchant/reports/sales?startDate&endDate) with 200 example (`totals`, `outlets[]` with outletId/outletName/timeZone/summary).

## Assumptions to verify against J1/J3 output
- Outlet response fields in examples: `id`, `name`, `timeZone`, `address`, `phoneNumber`, `active`, `usable`; list wrapper `allowedOutlets`, `activeOutlets`, `extraOutletPrice` (49000), `outlets`. Message strings ("Daftar outlet diambil.", "Outlet dibuat.", "Outlet aktif terakhir tidak dapat dinonaktifkan.", "Laporan penjualan gabungan diambil.") are placeholders; adjust to the final J1/J3 text. Create response must expose `id` for the `targetOutletId` script.
- Summary fields in the consolidated example are a subset of `FnbSalesSummaryResponse`.
- Runbook assumes `subscriptions.extra_outlets` as specified in M2 and that trial Premium does not add allowance.

## Notes
- No secrets or real emails in any file. Not run, not imported into Postman.
