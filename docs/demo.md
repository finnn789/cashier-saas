# Local demo (Docker Compose)

## Start

```bash
cp .env.example .env                 # PowerShell: Copy-Item .env.example .env
openssl rand -base64 32              # paste the output into CASHIER_JWT_SECRET_B64 in .env
                                     # PowerShell: [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
openssl rand -base64 32              # paste another value into CASHIER_GRPC_SERVICE_KEY in .env (required; shared by backend and ai-services)
openssl rand -base64 32              # paste a third, different value into AI_INTERNAL_API_KEY in .env (required by backend and ai-services)
docker compose up -d --build         # postgres + backend (profile "demo": Flyway migrations + demo seed)
docker compose ps                    # wait until backend is "healthy" (~1 min)
docker compose --profile ai up -d --build   # optional: also the Python AI service (needs Telegram/OpenRouter keys in .env)
docker compose down -v               # stop and delete data
```

| What | URL / port |
|---|---|
| Backend HTTP | http://localhost:8080 (override with `BACKEND_HTTP_PORT`) |
| Swagger UI | http://localhost:8080/swagger-ui.html (redirects to `/swagger-ui/index.html`) |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| gRPC | not published to the host; internal compose network only (`backend:9090`), requires `x-internal-service-key` = `CASHIER_GRPC_SERVICE_KEY`; the tenant must exist and be active (database check, no env allowlist) |
| PostgreSQL | localhost:5432, user `cashier`, password `POSTGRES_PASSWORD` (default `cashier_demo`), DBs `cashier_saas` and `cashier_ai` |
| AI service (profile `ai`) | http://localhost:8001 |

PostgreSQL 17 is used (not 16): `V1__initial_schema.sql` is a pg_dump 18 file containing `SET transaction_timeout`, which PG16 rejects.

## Demo data

Tenant **Kopi Demo** (F&B, FREETIER active), outlet **Kopi Demo - Pusat** (Asia/Jakarta, IDR, tax 11%, service charge 5%).
Tenant id `00000000-0000-4000-8000-0000000000b1`, outlet id `00000000-0000-4000-8000-0000000000d1`.

| Account | Password | Role |
|---|---|---|
| owner@demo.local | Demo123! | OWNERMERCHANT |
| cashier@demo.local | Demo123! | CASHIER |

Menu (IDR): Kopi - Espresso 18.000 (`...2001`), Kopi Susu Gula Aren 25.000 (`...2002`, options Ukuran Regular/Large +5.000, Tambahan Extra Shot +6.000 / Oat Milk +8.000), Cappuccino 28.000 (`...2003`, Ukuran); Non-Kopi - Matcha Latte 30.000 (`...2004`, Suhu Panas/Dingin +2.000); Makanan - Croissant Butter 22.000 (`...2005`), Nasi Goreng Kampung 35.000 (`...2006`). Product ids are `00000000-0000-4000-8000-00000000XXXX`; option ids `...4001` to `...4008`.

Ingredients (outlet stock, ids `00000000-0000-4000-8000-00000000XXXX`; the seed gives each a single `RESTOCK` movement "Stok awal", so balance equals ledger sum): Biji Kopi `...7001` 5000 g (reorder level 500), Susu `...7002` 20000 ml (2000), Gula Aren `...7003` 400 ml (500, so **LOW** from the start), Oat Milk `...7004` 6000 ml (1000), Bubuk Matcha `...7005` 1000 g (200), Boba `...7006` 3000 g (500; no recipe uses it), Croissant `...7007` 24 pcs (5). Quantities are plain numbers (no thousands separator). Recipes: Espresso 18 g Biji Kopi; Kopi Susu Gula Aren 18 g Biji Kopi + 120 ml Susu + 20 ml Gula Aren; Cappuccino 18 g + 150 ml Susu; Matcha Latte 5 g Bubuk Matcha + 150 ml Susu; Croissant Butter 1 pcs Croissant; add-on Extra Shot 18 g Biji Kopi; add-on Oat Milk 150 ml Oat Milk.

The seed (`src/main/resources/db/demo/R__demo_seed.sql`) is a Flyway repeatable migration loaded only by profile `demo`; every insert is `ON CONFLICT DO NOTHING`. It also inserts the `FNB` row in `business_types` (migrations do not). Older seed rows for dining tables and QR tokens may still exist in the database; they are unused after the cashier-first pivot.

## Verified curl walkthrough

All requests were run against the compose stack before the cashier-first pivot (replace the port if you changed it). Responses are `{success, code, message, data}`. After the pivot the QR and kitchen steps were removed and the order statuses are now `OPEN`/`COMPLETED`; the changed steps (4 to 6) were not re-run. Since the cash-shift milestone a payment requires an open shift: step 5b (open a shift) and the optional steps 8 and 9 (close the shift, refund) were added without being run, and the walkthrough has not been re-run with them.

```bash
B=http://localhost:8080
T=00000000-0000-4000-8000-0000000000b1   # tenant
O=00000000-0000-4000-8000-0000000000d1   # outlet

# 1. Login (account token, nextStep=SELECT_MERCHANT_OUTLET) -> 200
ACC=$(curl -s -H 'Content-Type: application/json' -d '{"email":"cashier@demo.local","password":"Demo123!"}' \
  $B/api/v1/auth/login | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 2. Select merchant/outlet (operational token, nextStep=OPERATE) -> 200
TOK=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $ACC" \
  -d "{\"tenantId\":\"$T\",\"outletId\":\"$O\"}" $B/api/v1/auth/select-merchant \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 3. List products -> 200
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/products

# 4. Create POS order (2 Espresso + Kopi Susu Large + Extra Shot) -> 201, status OPEN / paymentStatus UNPAID (a held bill)
OID=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -H "Idempotency-Key: demo-pos-$RANDOM$RANDOM" \
  -d '{"serviceType":"DINE_IN","customerLabel":"Budi","items":[{"productId":"00000000-0000-4000-8000-000000002001","quantity":2,"optionIds":[]},{"productId":"00000000-0000-4000-8000-000000002002","quantity":1,"note":"less ice","optionIds":["00000000-0000-4000-8000-000000004002","00000000-0000-4000-8000-000000004003"]}]}' \
  $B/api/v1/cashier/outlets/$O/fnb/orders | python -c "import sys,json;print(json.load(sys.stdin)['data']['orderId'])")

# 5. Optional: replace the items of the held bill (only while OPEN/UNPAID) -> 200. Not run here: it re-sends the
#    same items, so the totals and stock numbers in the steps below stay as they are.
curl -s -X PUT -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d '{"serviceType":"DINE_IN","customerLabel":"Budi","items":[{"productId":"00000000-0000-4000-8000-000000002001","quantity":2,"optionIds":[]},{"productId":"00000000-0000-4000-8000-000000002002","quantity":1,"note":"less ice","optionIds":["00000000-0000-4000-8000-000000004002","00000000-0000-4000-8000-000000004003"]}]}' \
  $B/api/v1/cashier/outlets/$O/fnb/orders/$OID/items

# 5b. Open a cash shift for the paying cashier -> 201. Payments return 409 "Buka shift terlebih dahulu ..." without it.
#     The shift stays open for the rest of this document (one open shift per user and outlet).
#     Optional, not run: GET .../fnb/shifts/opening-suggestion returns {openingCash, sourceShiftId} = the counted cash of the
#     last closed shift of the outlet (0.00 / null when there is none); the UI prefills the opening cash with it.
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK"   -d '{"openingCash":100000.00}' $B/api/v1/cashier/outlets/$O/fnb/shifts

# 6. Pay CASH -> 200, order becomes COMPLETED / PAID (total 83.520 incl. 11% tax + 5% service; change 116.480 on 200.000 tendered)
#    The receipt number in the response is INV-<yyyyMMdd>-<NNNN>: sequential per outlet per day (outlet time zone).
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d "{\"method\":\"CASH\",\"amountTendered\":200000.00,\"idempotencyKey\":\"demo-pay-$OID\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/orders/$OID/payments

# 6b. Optional, not run: record-only non-cash payment (QRIS/Transfer/Debit are only recorded, no provider is called).
#     The demo seed does not create payment labels (V8 seeds the labels of outlets that already exist when it runs, and
#     onboarding creates them for new outlets), so the owner creates one first ($OWNER_TOK as in step 7, 201), the cashier lists the
#     active labels and pays another OPEN order with the exact total and a labelId (amountTendered must equal the total).
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" -d '{"name":"QRIS","displayOrder":1}' \
  $B/api/v1/merchant/outlets/$O/fnb/payment-labels
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/payment-labels
#   -d "{\"method\":\"MANUAL_NON_CASH\",\"amountTendered\":<exact total>,\"labelId\":\"$LABEL\",\"idempotencyKey\":\"demo-pay-nc-$OID2\"}"
#   .../fnb/orders/$OID2/payments  (without labelId: 400 "Pilih label pembayaran non-tunai.")

# 6c. Optional, not run: petty cash during the open shift (header Idempotency-Key, amount > 0, description) -> 201; no open shift -> 409
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -H "Idempotency-Key: demo-expense-$RANDOM$RANDOM" \
  -d '{"amount":15000.00,"description":"Beli es batu"}' $B/api/v1/cashier/outlets/$O/fnb/shifts/current/expenses
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/shifts/current/expenses

# 6d. Optional, not run: receipt (paid or refunded orders; refunded ones carry refunded=true and a REFUND marker; footer from business settings)
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/orders/$OID/receipt
# Order list filters: ...fnb/orders?from=2026-11-14&to=2026-11-14 and ...fnb/orders?orderNumber=<number>

# 7. Sales report (owner only: repeat steps 1-2 with owner@demo.local) -> 200
curl -s -H "Authorization: Bearer $OWNER_TOK" "$B/api/v1/merchant/outlets/$O/fnb/reports/sales"
# CSV: .../fnb/reports/sales/csv

# 8. Optional, not run: current shift with the live cash summary, then close it -> 200.
#    expected = opening 100000 + cash in (tendered - change) - cash refunds - petty-cash expenses; difference = counted - expected
#    (totalsByMethod lists one row per method and label; the summary is the daily report)
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/shifts/current
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK"   -d '{"countedCash":100000.00,"note":"Tutup shift demo"}' $B/api/v1/cashier/outlets/$O/fnb/shifts/current/close

# 9. Optional, not run: owner refund of the paid order (owner only, full refund). A CASH refund needs the OWNER's own open shift.
#    -> 200, paymentStatus REFUNDED, stock returned by SALE_REVERSAL movements, the order drops out of the sales report.
#    Do it after the inventory walkthrough below if you want to keep its seeded stock numbers.
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK"   -d '{"openingCash":0.00}' $B/api/v1/cashier/outlets/$O/fnb/shifts
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" -H "Idempotency-Key: demo-refund-$OID"   -d '{"reason":"Pelanggan komplain"}' $B/api/v1/merchant/outlets/$O/fnb/orders/$OID/refund
```

Owner-only endpoints (categories, products CRUD, reports, shift list/force close, refund) are under `/api/v1/merchant/outlets/{outletId}/fnb/...`; cashier endpoints under `/api/v1/cashier/outlets/{outletId}/fnb/...`.

## Inventory and recipes walkthrough

Verification: executed on 2026-11-10 by the project orchestrator, running the bash blocks of this document unchanged (only the port) in order, against the backend jar built from the branch that contains this section (`java -jar`, profile `demo`, Flyway V1-V5 plus the demo seed, PostgreSQL 17 in a throwaway Docker container). Every "expected" value below matched the response. It was not run through `docker compose`, and `python` was provided by another interpreter than the host's. Two things were not exercised: the Postman collection and anything over a real network between separate hosts.

Continues the walkthrough above in the same shell: it needs `$B`, `$T`, `$O` and `$TOK` (the **cashier** operational token from steps 1-2). It creates its own ingredient, product and option group so the numbers below do not depend on how much stock earlier runs consumed; a random suffix makes every name and key unique, so it can be re-run. Side effects: it adds one ingredient, one product and one option group, and attaches that group to the seeded Espresso; `docker compose down -v` resets everything. Numbers marked "expected" come from the seed and the recipe arithmetic in the code and were confirmed by the run described above (quantities print as floats such as `-72.0` because the helper uses Python). Seed ids are the ones listed in "Demo data"; JSON field names are those of the request/response DTOs.

```bash
# 0. Owner operational token (same two calls as steps 1-2, with the owner account) and a unique suffix
OACC=$(curl -s -H 'Content-Type: application/json' -d '{"email":"owner@demo.local","password":"Demo123!"}' \
  $B/api/v1/auth/login | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
OWNER_TOK=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OACC" \
  -d "{\"tenantId\":\"$T\",\"outletId\":\"$O\"}" $B/api/v1/auth/select-merchant \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
SUF=$RANDOM$RANDOM
ESP=00000000-0000-4000-8000-000000002001     # seeded Espresso
KOPI=00000000-0000-4000-8000-000000007001    # seeded Biji Kopi (g)
SUSU=00000000-0000-4000-8000-000000007002    # seeded Susu (ml)

# 1. Owner: create an ingredient with initial stock 150 ml (reorder level 100) -> 201, stockStatus OK.
#    The initial stock is written as a RESTOCK movement "Stok awal".
SYR=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d "{\"name\":\"Sirup Vanila $SUF\",\"unit\":\"ml\",\"reorderLevel\":100,\"initialQuantity\":150}" \
  $B/api/v1/merchant/outlets/$O/fnb/inventory/items | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo $SYR

# 2. Owner: create a product and set its recipe (replace-all): 18 g Biji Kopi + 150 ml Susu per unit -> 200
LATTE=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d "{\"categoryId\":\"00000000-0000-4000-8000-000000001001\",\"name\":\"Latte Vanila $SUF\",\"price\":30000.00}" \
  $B/api/v1/merchant/outlets/$O/fnb/products | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
curl -s -X PUT -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d "{\"lines\":[{\"inventoryItemId\":\"$KOPI\",\"quantityPerUnit\":18},{\"inventoryItemId\":\"$SUSU\",\"quantityPerUnit\":150}]}" \
  $B/api/v1/merchant/outlets/$O/fnb/products/$LATTE/recipe

# 3. Owner: reusable option group "Sirup" (optional, at most 1) with one add-on "Vanila" (+3.000) whose recipe is
#    20 ml of the new ingredient, then attach the SAME group to two products (replace-all per product) -> 201 / 200
GRP=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d '{"name":"Sirup","required":false,"minimumSelections":0,"maximumSelections":1,"displayOrder":3}' \
  $B/api/v1/merchant/outlets/$O/fnb/option-groups | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
OPT=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d '{"name":"Vanila","priceAdjustment":3000.00,"available":true,"displayOrder":1}' \
  $B/api/v1/merchant/outlets/$O/fnb/option-groups/$GRP/options | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
curl -s -X PUT -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d "{\"lines\":[{\"inventoryItemId\":\"$SYR\",\"quantityPerUnit\":20}]}" \
  $B/api/v1/merchant/outlets/$O/fnb/option-groups/$GRP/options/$OPT/recipe
for P in $LATTE $ESP; do
  curl -s -X PUT -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
    -d "{\"groupIds\":[\"$GRP\"]}" $B/api/v1/merchant/outlets/$O/fnb/products/$P/option-groups; echo; done
# the cashier sees the same group id under both products
for P in $LATTE $ESP; do
  curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/products/$P/option-groups; echo; done

# 4. Cashier: POS takeaway order, 3 Latte Vanila + 1 Espresso with the Vanila add-on -> 201
#    expected: subtotal 120.000 (3 x 33.000 + 21.000), total 139.200 (11% tax + 5% service)
OID=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -H "Idempotency-Key: demo-inventory-order-1-$SUF" \
  -d "{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Dewi\",\"items\":[{\"productId\":\"$LATTE\",\"quantity\":3,\"optionIds\":[\"$OPT\"]},{\"productId\":\"$ESP\",\"quantity\":1,\"optionIds\":[\"$OPT\"]}]}" \
  $B/api/v1/cashier/outlets/$O/fnb/orders | python -c "import sys,json;print(json.load(sys.stdin)['data']['orderId'])")

# 5. Pay CASH -> 200. Stock is deducted inside this payment.
#    expected: changeAmount 10800.00 and stockWarnings ["Sirup Vanila <SUF>: sisa 70 ml (menipis)"] (150 - 4 x 20 = 70, at or below reorder level 100)
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d "{\"method\":\"CASH\",\"amountTendered\":150000.00,\"idempotencyKey\":\"demo-inv-pay-1-$SUF\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/orders/$OID/payments

# 6. SALE movements of that order: one per ingredient, reason "Penjualan <orderNumber>", orderId set
#    expected deltas: 7001 -72.0 (3 x 18 + 1 x 18), 7002 -450.0 (3 x 150), new ingredient -80.0 (4 x 20, the shared add-on)
for I in $KOPI $SUSU $SYR; do
  curl -s -H "Authorization: Bearer $TOK" "$B/api/v1/cashier/outlets/$O/fnb/inventory/items/$I/movements?size=50" \
    | python -c "import sys,json;[print(m['inventoryItemId'][-4:],m['type'],m['quantityDelta'],m['reason']) for m in json.load(sys.stdin)['data'] if m['orderId']==sys.argv[1]]" $OID; done

# 7. Stock list filtered by status (OK, LOW, OUT, NEGATIVE) -> 200; expected: the new ingredient at 70.0 ml and the seeded Gula Aren are LOW
curl -s -H "Authorization: Bearer $TOK" "$B/api/v1/cashier/outlets/$O/fnb/inventory/items?status=LOW" \
  | python -c "import sys,json;[print(i['name'],i['quantityOnHand'],i['unit'],i['stockStatus']) for i in json.load(sys.stdin)['data']]"

# 8. Cashier RESTOCK +100 (idempotent: the same key and body returns the SAME stored movement, so both ids are equal) -> 201
RS="{\"type\":\"RESTOCK\",\"quantity\":100,\"reason\":\"Belanja pasar\",\"idempotencyKey\":\"demo-inv-restock-$SUF\"}"
M1=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -d "$RS" \
  $B/api/v1/cashier/outlets/$O/fnb/inventory/items/$SYR/movements | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
M2=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -d "$RS" \
  $B/api/v1/cashier/outlets/$O/fnb/inventory/items/$SYR/movements | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo "$M1 $M2"

# 9. Cashier WASTE 30 (a positive quantity is stored as delta -30; a reason is required) -> 201
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d "{\"type\":\"WASTE\",\"quantity\":30,\"reason\":\"Tumpah\",\"idempotencyKey\":\"demo-inv-waste-$SUF\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/inventory/items/$SYR/movements

# 10. ADJUSTMENT is owner only: the cashier gets 404 (nothing changes), the owner gets 201 (signed quantity, reason required)
curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d "{\"type\":\"ADJUSTMENT\",\"quantity\":-20,\"reason\":\"Stok opname\",\"idempotencyKey\":\"demo-inv-adj-c-$SUF\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/inventory/items/$SYR/movements
curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' -H "Authorization: Bearer $OWNER_TOK" \
  -d "{\"type\":\"ADJUSTMENT\",\"quantity\":-20,\"reason\":\"Stok opname\",\"idempotencyKey\":\"demo-inv-adj-o-$SUF\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/inventory/items/$SYR/movements

# 11. Balance and full ledger of the new ingredient. expected: 150 - 80 + 100 - 30 - 20 = 120 ml, OK; five movements
#     (RESTOCK 150, SALE -80, RESTOCK 100, WASTE -30, ADJUSTMENT -20), newest first, whose deltas sum to the balance
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/inventory/items \
  | python -c "import sys,json;[print(i['name'],i['quantityOnHand'],i['stockStatus']) for i in json.load(sys.stdin)['data'] if i['id']==sys.argv[1]]" $SYR
curl -s -H "Authorization: Bearer $TOK" "$B/api/v1/cashier/outlets/$O/fnb/inventory/items/$SYR/movements?size=50" \
  | python -c "import sys,json;[print(m['type'],m['quantityDelta'],m['reason']) for m in json.load(sys.stdin)['data']]"

# 12. Negative stock never blocks a sale: 8 more Latte Vanila need 160 ml but only 120 ml are left -> payment 200
#     expected: total 306240.00, changeAmount 3760.00, stockWarnings contains "Sirup Vanila <SUF>: sisa -40 ml (minus)"
OID2=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -H "Idempotency-Key: demo-inventory-order-2-$SUF" \
  -d "{\"serviceType\":\"TAKEAWAY\",\"customerLabel\":\"Rudi\",\"items\":[{\"productId\":\"$LATTE\",\"quantity\":8,\"optionIds\":[\"$OPT\"]}]}" \
  $B/api/v1/cashier/outlets/$O/fnb/orders | python -c "import sys,json;print(json.load(sys.stdin)['data']['orderId'])")
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d "{\"method\":\"CASH\",\"amountTendered\":310000.00,\"idempotencyKey\":\"demo-inv-pay-2-$SUF\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/orders/$OID2/payments
curl -s -H "Authorization: Bearer $TOK" "$B/api/v1/cashier/outlets/$O/fnb/inventory/items?status=NEGATIVE" \
  | python -c "import sys,json;[print(i['name'],i['quantityOnHand'],i['unit'],i['stockStatus']) for i in json.load(sys.stdin)['data']]"
```

What the steps demonstrate:

- Recipes belong to a product or to an add-on option; the add-on recipe of a shared option applies to every product that has the group, once per ordered unit of the line.
- Stock changes only when an order becomes paid (cash or non-cash), inside the payment transaction, with one `SALE` movement per (order, ingredient). An unpaid order, and a cancelled unpaid order, deduct nothing, and a paid order cannot be cancelled. The only reversal is the owner refund, which writes one `SALE_REVERSAL` movement per ingredient.
- The seeded steps 4 and 6 of the first walkthrough already consume stock with the seeded recipes. Observed on a fresh stack (confirmed by the run described above): the step 6 payment response carries `stockWarnings` of `["Gula Aren: sisa 380 ml (menipis)"]` (400 - 20 ml, reorder level 500).
- RESTOCK and WASTE are open to CASHIER and OWNERMERCHANT; ADJUSTMENT, ingredient create/update/deactivate and recipes are OWNERMERCHANT only.
- A movement key is unique per tenant: the same key with a different body is a `409`. Known limit: two parallel requests with the same key may return `409` to the loser instead of a replay (the balance still changes once).

## Notes

- Cash shifts: payments need an open shift of the user who pays. The inventory walkthrough below pays as the same cashier, so the shift opened in step 5b is reused.
- Only paid, non-cancelled orders appear in reports.
- Refresh cookie `Secure` flag is off in the demo profile so it works over plain HTTP.
- Payments are record-only (M5): `CASH` and `MANUAL_NON_CASH` with an owner-defined label; there is no payment gateway, mock or real, and no related setting. Steps 6b to 6d (labels, petty cash, receipt, filters) and the order note (`orderNote`) were not run against a live stack.
- The root `.env` is also read by the backend outside Docker (`spring.config.import`); inside the container no `.env` is shipped.
- Staff sync to the AI service is **in progress**: the backend pushes staff data (tenant, name, normalized phone, role) to Ai-services on owner profile completion and cashier invitation claim, authenticated by `AI_INTERNAL_API_KEY`. Compose points the backend at `http://ai-services:8001` unless `AI_SERVICE_BASE_URL` is set. Not yet verified end to end.
- Telegram staff identification is **in progress**: users verify by sharing their own Telegram contact. `AI_TENANT_ID` is only a development fallback and never overrides a verified tenant.
