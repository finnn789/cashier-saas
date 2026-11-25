# TASK-087 A1 report: API tests for read-model enrichment

Status: PASS (on a patched scratch copy of the collection, see "Run caveats"; the repo collection itself cannot be loaded by the installed Postman CLI)
baseUrl: http://localhost:8080 (backend up, /public/plans = 200)

## Command (credentials redacted)
Run from a scratch copy of `postman/collections/KedaiMbo API` (copy under the session scratchpad, not in the repo):
```
postman collection run "KedaiMbo API" \
  -i "01 - Authentication/Register Account" -i "03 - Onboarding" \
  -i "06 - F&B Categories/Create Category" -i "07 - F&B Products/Create Product" -i "07 - F&B Products/List Products" \
  -i "15 - F&B POS Orders/Checkout POS Order" -i "15 - F&B POS Orders/List Orders" \
  -i "16 - F&B Shifts/Open Shift" -i "17 - F&B Payments/Pay Order Cash" -i "23 - F&B Inventory" \
  --env-var baseUrl=http://localhost:8080 --env-var email=<throwaway> --env-var password=<random>
```
`--output target/postman-runs` was dropped: CLI answers "--output is available only to Postman Enterprise teams". Throwaway owner generated per run, nothing written to files.

## Totals
19 requests, 11 test-scripts, 8 assertions: 8 passed, 0 failed. (Earlier run, before the Register fix below: 8 failed, all 401 because registration returned 400.)

## Assertions (all pass)
1. `07/List Products`: created product has `categoryName` "Makanan".
2. `15/Checkout POS Order`: 201, `cashierName` = owner fullName, no `methodLabel`/`receiptNumber` keys.
3. `15/List Orders` (OPEN/UNPAID): same order has `cashierName`, no `methodLabel`/`receiptNumber`.
4. `23/Put Product Recipe` (quantityPerUnit 2, no cost yet): `lines[0].costEstimate` null.
5. `23/Restock Inventory Item` (RESTOCK 10 @ 12.3425): 201, `actorName` = owner fullName.
6. `23/Get Product Recipe`: `costEstimate` = 24.69 (2 x 12.3425 = 24.685, HALF_UP).
7. `23/List Movements`: RESTOCK row `actorName` = owner fullName.
8. `23/List Paid Orders` (paymentStatus=PAID): order has `methodLabel` "CASH" and `receiptNumber` equal to the receipt number of the paid order.

## Request files modified / created (all in reserved folders)
- Edited: `07 - F&B Products/List Products.request.yaml`, `15 - F&B POS Orders/Checkout POS Order.request.yaml` (existing capture code untouched, test appended), `15 - F&B POS Orders/List Orders.request.yaml` (same).
- New folder `23 - F&B Inventory` (with `.resources/definition.yaml`, order 23000): `Create Inventory Item`, `Put Product Recipe`, `Restock Inventory Item`, `Get Product Recipe`, `List Movements`, `Get Receipt For Order List`, `List Paid Orders`. Request-level scripts capture the new collection variables `inventoryItemId` and `paidReceiptNumber` (not declared in `definition.yaml`, which I may not edit; they work as ad-hoc collection variables).

## Deviations from the packet
- Movement endpoints are per item, not `.../fnb/inventory/movements`: `POST|GET /api/v1/cashier/outlets/{outletId}/fnb/inventory/items/{id}/movements`. Used the real paths from `FnbInventoryController`. Restock body needs `idempotencyKey` (uses `{{$guid}}`) and `quantity`.
- "receiptNumber equal to the payment response": `17/Pay Order Cash` is not editable and does not store the number, so folder 23 re-reads it via `GET .../orders/{orderId}/receipt` (the stored payment's receipt number) and compares to the list. Not literally the pay response body.
- Folder 23 runs after 17 by collection order, so the paid-list check is a new request in 23 (`List Paid Orders`) rather than the 15 `List Orders` request, whose URL filter is OPEN/UNPAID and which runs before payment.

## Run caveats / findings (not fixed, outside reserved paths)
1. Installed Postman CLI 1.76.0 (1.93.1 available) cannot load the repo collection: "Failed to load V3 collection: Invalid input for model". Cause: it rejects request-level `scripts` with `type: http:afterResponse` (accepts `afterResponse`), while collection-level `definition.yaml` needs the `http:` prefix. This already affects pre-existing requests in folders 05, 15, 16, 18, 20-22, so it is not specific to this task. Workaround used: scratch copy with `http:afterResponse` -> `afterResponse` in request files only. Real fix is `postman update` (not done by me) or aligning the script type.
2. `01 - Authentication/Register Account` body lacks `phoneNumber`, now mandatory (`RegisterUserRequest`): without it register returns 400 "Nomor telepon wajib diisi." Patched in the scratch copy only (`"phoneNumber": "081234567890"`). The repo file needs the same one-line fix (folder 01, not mine).
3. The leftover scratch copy and the throwaway test accounts remain (local dev DB).

## Not run
Nothing from the packet skipped. No data-destroying requests used.

## Open questions
- Should `inventoryItemId` / `paidReceiptNumber` be declared in `definition.yaml` by the orchestrator?
- Orchestrator re-run needs the two fixes in "Run caveats" (CLI update or script type change; phoneNumber in Register body).
