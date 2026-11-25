# TASK-102: Verify per-outlet menu prices end to end (API tests)

Status: PREPARED, NOT DELEGATED (memory v100; the user will delegate). Run AFTER TASK-101 and TASK-103 J1 are compiled (both touch catalog access). Delegating this task counts as the user's explicit request to test. Needs a running local backend (started by the user) and a seeded owner with two outlets.

## User instruction (2026-11-25)
"Saya maunya harga menu bisa berbeda per outlet." Products are outlet-scoped (`products.outlet_id` + own `price`), so the behaviour already exists; this task proves it with API tests instead of building anything.

## Verified facts (orchestrator, source read 2026-11-25)
- Catalog reads `GET /api/v1/cashier/outlets/{outletId}/fnb/products`; owner price change `PUT /api/v1/merchant/outlets/{outletId}/fnb/products/{id}` (needs `priceChangeReason` when the price changes; audited `FNB_PRODUCT_PRICE_CHANGED`). Token is bound to one outlet: switch outlet with `POST /api/v1/auth/select-merchant`.
- Orders store product name and price as snapshots (`OrderItemEntity.productNameSnapshot`, `unitPriceSnapshot`); totals are server-calculated.
- Reports: per outlet `GET /api/v1/merchant/outlets/{outletId}/fnb/reports/sales`, consolidated `GET /api/v1/merchant/reports/sales`.
- Postman collection `postman/collections/KedaiMbo API/` (folders 01-23); request-level scripts must use `afterResponse` (see the existing collection; `http:afterResponse` fails sync). Run from the `postman/collections` directory.

## Scenarios (A1 adds them as `pm.test` assertions)
1. Outlet A and outlet B each have "Es Teh": A = 5.000, B = 6.000 (create in B, or copy with TASK-101 and then change B's price).
2. Change B's price to 7.000 (with reason): A's product still reads 5.000.
3. POS order in A with 2x Es Teh: line unit price 5.000, total 10.000 (server). Same in B: 7.000 / 14.000.
4. Change A's price to 5.500 after the order: the earlier A order detail still shows 5.000 (snapshot).
5. Per-outlet sales report of A and B show their own totals; the consolidated report equals A + B.
6. With an A token, reading B's product id through A's path returns 404 (no cross-outlet leak).

## Wave 1

### A1 (`api-test-worker`)
Reserved: a new folder `postman/collections/KedaiMbo API/24 - Per-Outlet Prices/` (requests + `afterResponse` scripts), plus environment variables it needs in the existing environment file only if the collection already keeps them there.
Report: `reports/TASK-102-A1.md` (exact Postman CLI command, pass/fail per scenario, failing response bodies).
Acceptance: all 6 scenarios pass against the running backend, or each failure is reported with the response; no production code changed.
