# Local demo (Docker Compose)

## Start

```bash
cp .env.example .env                 # PowerShell: Copy-Item .env.example .env
openssl rand -base64 32              # paste the output into CASHIER_JWT_SECRET_B64 in .env
                                     # PowerShell: [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
openssl rand -base64 32              # paste another value into CASHIER_GRPC_SERVICE_KEY in .env (required; shared by backend and ai-services)
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
| gRPC | not published to the host; internal compose network only (`backend:9090`), requires `x-internal-service-key` = `CASHIER_GRPC_SERVICE_KEY` |
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

Tables and public QR tokens (stored in plain text in `table_qr_tokens.token`, 43 chars `[A-Za-z0-9_-]`; the seed inserts the plain token so these work as-is):

| Table | id | QR token | Public menu |
|---|---|---|---|
| Meja 01 | `00000000-0000-4000-8000-000000005001` | `demo-qr-meja-01-kopi-demo-public-token00000` | `/api/v1/public/fnb/menu/{token}` |
| Meja 02 | `...5002` | `demo-qr-meja-02-kopi-demo-public-token00000` | same |
| Meja 03 | `...5003` | `demo-qr-meja-03-kopi-demo-public-token00000` | same |
| Outlet QR (takeaway) | - | `demo-qr-outlet-kopi-demo-public-token000000` | same |

The seed (`src/main/resources/db/demo/R__demo_seed.sql`) is a Flyway repeatable migration loaded only by profile `demo`; every insert is `ON CONFLICT DO NOTHING`. It also inserts the `FNB` row in `business_types` (migrations do not).

## Verified curl walkthrough

All requests were run against the compose stack (replace the port if you changed it). Responses are `{success, code, message, data}`.

```bash
B=http://localhost:8080
T=00000000-0000-4000-8000-0000000000b1   # tenant
O=00000000-0000-4000-8000-0000000000d1   # outlet

# 1. Public menu by QR token (no auth) -> 200
curl -s $B/api/v1/public/fnb/menu/demo-qr-meja-01-kopi-demo-public-token00000

# 2. Public QR order (Idempotency-Key must be 16-128 chars) -> 201, returns orderToken
curl -s -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-qr-order-0001' \
  -d '{"serviceType":"DINE_IN","customerLabel":"Sari","items":[{"productId":"00000000-0000-4000-8000-000000002005","quantity":1,"optionIds":[]}]}' \
  $B/api/v1/public/fnb/menu/demo-qr-meja-02-kopi-demo-public-token00000/orders

# 3. Login (account token, nextStep=SELECT_MERCHANT_OUTLET) -> 200
ACC=$(curl -s -H 'Content-Type: application/json' -d '{"email":"cashier@demo.local","password":"Demo123!"}' \
  $B/api/v1/auth/login | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 4. Select merchant/outlet (operational token, nextStep=OPERATE) -> 200
TOK=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $ACC" \
  -d "{\"tenantId\":\"$T\",\"outletId\":\"$O\"}" $B/api/v1/auth/select-merchant \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 5. List products -> 200
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/products

# 6. Create POS order (2 Espresso + Kopi Susu Large + Extra Shot, Meja 01) -> 201
OID=$(curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" -H "Idempotency-Key: demo-pos-$RANDOM$RANDOM" \
  -d '{"serviceType":"DINE_IN","tableId":"00000000-0000-4000-8000-000000005001","customerLabel":"Budi","items":[{"productId":"00000000-0000-4000-8000-000000002001","quantity":2,"optionIds":[]},{"productId":"00000000-0000-4000-8000-000000002002","quantity":1,"note":"less ice","optionIds":["00000000-0000-4000-8000-000000004002","00000000-0000-4000-8000-000000004003"]}]}' \
  $B/api/v1/cashier/outlets/$O/fnb/orders | python -c "import sys,json;print(json.load(sys.stdin)['data']['orderId'])")

# 7. Pay CASH -> 200 (total 83.520 incl. 11% tax + 5% service; change 116.480 on 200.000 tendered)
curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $TOK" \
  -d "{\"method\":\"CASH\",\"amountTendered\":200000.00,\"idempotencyKey\":\"demo-pay-$OID\"}" \
  $B/api/v1/cashier/outlets/$O/fnb/orders/$OID/payments

# 8. Kitchen: list queue, then prepare -> ready -> complete (each 200)
curl -s -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/kitchen/orders
for s in prepare ready complete; do
  curl -s -X POST -H "Authorization: Bearer $TOK" $B/api/v1/cashier/outlets/$O/fnb/kitchen/orders/$OID/$s; done

# 9. Sales report (owner only: repeat steps 3-4 with owner@demo.local) -> 200
curl -s -H "Authorization: Bearer $OWNER_TOK" "$B/api/v1/merchant/outlets/$O/fnb/reports/sales"
# CSV: .../fnb/reports/sales/csv
```

Owner-only endpoints (tables, categories, products CRUD, reports) are under `/api/v1/merchant/outlets/{outletId}/fnb/...`; cashier endpoints under `/api/v1/cashier/outlets/{outletId}/fnb/...`.

## Notes

- Only paid, non-cancelled orders appear in reports.
- Refresh cookie `Secure` flag is off in the demo profile so it works over plain HTTP.
- The root `.env` is also read by the backend outside Docker (`spring.config.import`); inside the container no `.env` is shipped.
