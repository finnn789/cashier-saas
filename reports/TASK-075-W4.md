# TASK-075 W4 - Postman and docs

Status: done for the reserved scope. Nothing was executed (no Postman run, no Maven, no tests).

## Postman
- Deleted folders (YAML collection `postman/collections/KedaiMbo API/`): `10 - F&B Tables`, `11 - F&B Outlet QR`, `12 - Public F&B Menu & Orders` (also staged as deletions in the index, like the other TASK-074 deletions).
- `.resources/definition.yaml`: removed variables `tableId`, `qrToken`, `outletQrToken`, `orderToken`, `publicOrderKey` and the response-script cases for the six removed requests; added variables `orderId` and `posOrderKey` (pre-request key generator now `onboardingKey`, `saleKey`, `posOrderKey`); description updated (flow Create Product -> Checkout POS Order (OPEN) -> Replace Order Items -> payment -> COMPLETED).
- New folder `15 - F&B POS Orders` with `.resources/definition.yaml`, `Checkout POS Order.request.yaml` (POST, `Idempotency-Key: {{posOrderKey}}`, body serviceType/customerLabel/items, no tableId; captures `orderId`) and `Replace Order Items.request.yaml` (`PUT {{baseUrl}}/api/v1/cashier/outlets/{{outletId}}/fnb/orders/{{orderId}}/items`, no Idempotency-Key, same body shape).
  Note: the collection had NO POS checkout request (staff order requests were dropped earlier, commit ff20e83), so I added "Checkout POS Order" as the neighbour and source of `{{orderId}}`.
- `postman/KedaiMbo.postman_collection.json`: same changes (folders 10/11/12 removed, variables, description, prerequest key, new folder 15). Parses as valid JSON. `FnbCatalogController.postman_collection.json` and `postman/environments/` (empty dir) needed no change. Request files had no status examples (nothing to change to OPEN/COMPLETED/CANCELLED); no kitchen or realtime-ticket request existed.
- Kept: `GET /api/v1/public/plans` ("02 - Membership Plans / List Available Plans"), because `MembershipPlanCatalogController` still serves `/api/v1/public/plans`. ORCHESTRATOR ATTENTION: TASK-074 removed `permitAll` for `/api/v1/public/**` from `SecurityConfiguration`, so this plans route now falls under `anyRequest().authenticated()` (the request still has `auth: noauth`). Either re-permit `GET /api/v1/public/plans` in SecurityConfiguration or drop the request; I did not touch src.

## Docs
- Deleted: `docs/fnb-realtime.md`.
- `README.md`: tagline/intro, feature list (QR, tables, kitchen, realtime removed; hold bill + replace-items added), architecture and state diagrams (OPEN/UNPAID -> COMPLETED/PAID, CANCELLED), decision table (WebSocket ticket and AFTER_COMMIT rows removed, idempotency/lock rows reworded), tech stack (no WebSocket), test-list bullets, env table row `CASHIER_CORS_ALLOWED_ORIGINS` (property `app.cors.allowed-origins`), project structure, limitations (polling every 15 s + focus + after mutations, PRD 5 s not guaranteed; rate-limit groups AUTH/AUTHENTICATED/ANONYMOUS_OTHER; pivot note).
- `docs/architecture.md`: modules, security table (public/realtime rows removed), new CORS and rate-limit paragraph, order lifecycle (OPEN/COMPLETED/CANCELLED), checkout steps, new "Replace items of a held bill" section, payment (COMPLETED), kitchen section removed, realtime section replaced by "Order list refresh (polling)", V6 in persistence.
- `docs/demo.md`: QR/table tables and QR/kitchen curl steps removed, `tableId` dropped, steps renumbered (login 1, select 2, products 3, create held order 4, optional replace-items 5 (NOT run), pay 6, report 7), cross references fixed; the changed steps are stated as not re-run.
- `docs/fnb-operations.md`: kitchen/tables/QR/tableId removed, statuses OPEN/COMPLETED/CANCELLED, new "Replace items of a held bill" section, `status=OPEN&paymentStatus=UNPAID` hint.
- `docs/fnb-pay-first-backend.md`: rewritten (was the stage-1 QR slice) as a short Pay First contract plus V6 note.
- `docs/entity-organization.md`, `docs/database/legacy-mapping.md`: table/QR/kitchen mentions removed or marked removed from code (DB tables stay).
- `docs/contracts/cashier-service.grpc.schema.json` and `.test-messages.json`: status description/expectation now lists OPEN, COMPLETED, CANCELLED (both still valid JSON). Proto untouched.
- Not touched: `docs/progress/`, `reports/` (other than this file), `docs/database/laundry-*` (laundry READY status is legitimate), `docs/testing/error-handling.md` ("READY FOR TESTING").
- Outside my reserved scope, still stale: `prompts/PROMPT_FOR_FE_AGENT_FNB_API_INTEGRATION.md` line 7 references `docs\fnb-realtime.md`.

## Grep evidence
Command: `grep -rnE "tableId|/public/|kitchen|Kitchen|realtime|Realtime|WebSocket|websocket|qrToken|PREPARING|CONFIRMED|\"NEW\"|READY" postman docs README.md --exclude-dir=progress`
Remaining hits and why they are acceptable:
- `/public/plans` in the plans request (YAML + JSON): real, still existing route (see note above).
- `docs/architecture.md` "There is no WebSocket ..." and `docs/fnb-operations.md`, `docs/fnb-pay-first-backend.md`, `README.md` pivot notes: sentences stating that kitchen/QR/WebSocket were removed.
- `docs/database/laundry-v1-*.md`, `laundry-v1.sql`, `docs/database/README.md`: laundry order status READY (separate product module).
- `docs/testing/error-handling.md`: "READY FOR TESTING" delivery status word.
- `docs/demo.md` line 46: pivot note ("QR and kitchen steps were removed").
Old order statuses NEW/CONFIRMED/PREPARING no longer appear anywhere in postman/docs/README outside those items.

## Not executed
No Postman run, no Maven, no tests, no gRPC call. YAML/JSON validity checked only by parsing the two JSON contract files and the Postman JSON collection with Node.
