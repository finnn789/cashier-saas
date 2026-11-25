# PRD & Architecture — Kasir SaaS Multi-Outlet dengan AI

Nov 20, 2026 · @Arifin

**Versi:** 3.0 · **Status:** Aktif · **Menggantikan:** PRD v2.2 (14 Nov 2026, tersimpan di riwayat Git).

## 0. Rekonsiliasi dengan Kode yang Sudah Ada (mengikat)

Produk dibangun dengan **refactor** repo `cashier-saas` yang ada, bukan dari nol (keputusan 20 Nov 2026). Bila bagian lain dokumen ini bertentangan dengan tabel di bawah, tabel ini yang berlaku.

| Topik di dokumen ini | Yang berlaku |
| --- | --- |
| Java 21, Spring Boot 3, Gradle, Spring Modulith (§7–8, §14) | Java 17, Spring Boot 4.1.1, Maven wrapper, layout package `controller/service/repository/dto/entity` sesuai `AGENTS.md`. Batas modul nanti dijaga ArchUnit, tanpa memindah package. |
| Repo monorepo `kasir-ai/` (§8) | Tetap: backend di root, `Ai-services/` (Python), UI di repo terpisah `cashier-saas-ui`. |
| Argon2id, RS256, UUIDv7 (§9, §13) | BCrypt, JWT HS256 (`CASHIER_JWT_SECRET_B64`), UUID yang ada. |
| Uang `BIGINT` Rupiah (§4, §9) | `BigDecimal`/`numeric` (sudah konsisten di kode; tidak pernah floating point). |
| Postgres RLS (§9) | Isolasi di layer aplikasi + integration test (sudah ada). RLS = penguatan berikutnya, bukan syarat slice awal. |
| RabbitMQ, Redis, MinIO, outbox (§7, §10, §14) | Belum dipakai. Java ↔ Python lewat gRPC (baca) dan REST internal (ringkasan harian, sinkron member). Object storage baru ditambahkan saat slice OCR. |
| Python membaca Postgres lewat role read-only (§7, §10) | Python **tidak** membaca database Java; semua data lewat gRPC yang di-scope tenant/outlet oleh Java. |
| Role Owner/Manager/Kasir (§9, §13) | Saat ini `SUPERADMIN`, `OWNERMERCHANT`, `CASHIER`. Penambahan `MANAGER` **menunggu keputusan user**. |
| Mode offline PWA (§3 Should) | Ditunda sampai setelah pilot. |
| Transfer stok antar outlet (FR-IN-04) | Slice ke-4/5, setelah kebocoran, margin, OCR. |
| AI Command Agent tulis (FR-AI-03) | Diadopsi bertahap dengan approval wajib; aturan lama "AI read-only" dicabut hanya untuk alur proposal + approval. |
| Chat AI | Telegram (sudah ada) tetap; command bar web ditambahkan. |
| Provider LLM (§17) | OpenRouter, model default DeepSeek V4.1 Flash (`AI_MODEL`); OCR memakai model vision lewat OpenRouter. |
| Metode biaya bahan (§17) | Moving average (sudah ada, V9). |
| Paket langganan | Tetap berlaku, lihat Lampiran A (tidak disebut di dokumen asli). |

**Urutan slice:** (1) stok opname + laporan kebocoran, (2) HPP/margin menu + peringatan, (3) pembelian + OCR nota, (4) AI Command Agent dengan approval + harga per outlet, (5) Manager/PIN, transfer stok, RLS, ArchUnit. Offline setelah pilot.

**Sudah ada di kode (tidak dibangun ulang):** onboarding tenant + outlet, refresh token, multi-outlet + laporan konsolidasi, katalog + option group, bahan + resep + stok ledger (SALE/RESTOCK/WASTE/ADJUSTMENT/SALE_REVERSAL), stok opname batch, harga pokok moving average + snapshot COGS, POS + hold bill + diskon + bayar tunai/non-tunai berlabel + struk, shift + kas kecil, void/refund owner, audit log + viewer, rate limit, paket + kuota AI, bot Telegram + ringkasan harian + tool baca AI.

## 1. Ringkasan Produk

Produk ini adalah kasir SaaS multi-outlet untuk resto/cafe UMKM yang menjual satu janji: **AI menemukan uang owner yang bocor di tiap outlet**. Pembeda dari Majoo, Moka, dan ESB bukan chatbot laporan, tetapi tiga fitur yang menghitung kerugian nyata dalam Rupiah.

**Tiga fitur pembeda (MVP):**

1. **Detektor kebocoran bahan** — membandingkan pemakaian teoritis (resep × terjual) dengan stok opname aktual per outlet, lalu menjelaskan selisihnya.
2. **HPP live dari foto nota** — kasir memotret nota belanja pasar, OCR memperbarui harga bahan, sistem menghitung ulang HPP dan memberi peringatan margin.
3. **AI Command Agent** — owner memberi perintah bahasa natural ("naikkan harga es teh Rp1.000 di semua outlet kecuali Padang"); AI menyusun usulan perubahan, owner menyetujui, sistem mengeksekusi dengan audit log.

Pelengkap: ringkasan penjualan harian otomatis setelah tutup outlet.

**Tujuan MVP:**

- Satu alur demo end-to-end yang berjalan: transaksi kasir → stok terpotong → opname → laporan kebocoran → perintah AI → perubahan harga tercatat.
- Menjadi portfolio backend yang menunjukkan modular monolith, multi-tenancy, event-driven, integrasi AI yang aman, dan testing yang serius.
- Siap dipakai 1–3 outlet uji coba (pilot) dengan data nyata.

## 2. Persona & Masalah

Masalah inti: owner dengan 2–10 outlet tidak bisa hadir di semua cabang. Laporan omzet ada, tetapi tidak menjawab "uang saya hilang di mana".

| Persona | Konteks | Masalah utama | Yang dibutuhkan dari produk |
| --- | --- | --- | --- |
| Owner multi-outlet | 2–10 outlet, sering pantau dari HP | Bahan boros/hilang tidak terlihat; HPP basi saat harga bahan naik; ubah harga di banyak outlet itu repot | Angka kebocoran per outlet, peringatan margin, perintah cepat lewat AI |
| Manager outlet | Pegang 1 outlet, buka/tutup shift, opname | Opname manual di kertas; belanja pasar tidak tercatat rapi | Form opname cepat, foto nota langsung jadi data |
| Kasir | Tablet di meja kasir, ramai saat jam makan | Internet putus, menu banyak, salah input | Kasir cepat, bisa offline, tombol besar |

**Problem statement:** UMKM F&B kehilangan margin karena (a) pemakaian bahan melebihi resep tanpa ketahuan, (b) harga jual tidak mengikuti harga bahan yang fluktuatif, dan (c) owner tidak punya waktu membaca dashboard.

## 3. Scope MVP & Non-goals

MVP berfokus pada satu alur uang: jual → stok → HPP → kebocoran → tindakan. Fitur lain menunggu sampai ada pilot.

| Prioritas | Fitur | Alasan |
| --- | --- | --- |
| Must | Multi-tenant, multi-outlet, RBAC (Owner, Manager, Kasir) | Fondasi SaaS |
| Must | Kasir: order, diskon sederhana, bayar tunai/QRIS manual, struk | Sumber data penjualan |
| Must | Shift buka/tutup + setoran kas | Dasar tutup hari & ringkasan |
| Must | Menu + resep (BOM) + bahan + satuan | Syarat hitung HPP & pemakaian teoritis |
| Must | Stock ledger, pembelian, opname, transfer antar outlet | Syarat hitung kebocoran |
| Must | HPP & margin per menu, peringatan margin | Fitur pembeda #2 |
| Must | OCR nota belanja → draft pembelian | Fitur pembeda #2 |
| Must | Laporan kebocoran per outlet + penjelasan AI | Fitur pembeda #1 |
| Must | AI Command Agent dengan approval + audit log | Fitur pembeda #3 |
| Should | Ringkasan harian AI (in-app, lalu WhatsApp) | Nilai harian untuk owner |
| Should | Mode offline kasir (PWA) | Realita internet UMKM |
| Could | Saran transfer stok antar outlet | Butuh data historis |
| Could | Prediksi belanja harian | Butuh data ≥ 4 minggu |

**Non-goals MVP:** integrasi GoFood/GrabFood, payment gateway QRIS dinamis, akuntansi penuh, HRIS/payroll, loyalty, self-order QR, analisis nutrisi, aplikasi native.

## 4. Functional Requirements

Setiap requirement punya acceptance criteria yang bisa diuji otomatis. ID dipakai di nama test dan commit.

| ID | Modul | Requirement | Acceptance criteria |
| --- | --- | --- | --- |
| FR-ID-01 | identity | Owner daftar → tenant + outlet pertama dibuat | Satu transaksi DB; email unik global; owner langsung login |
| FR-ID-02 | identity | Undang user dengan role per outlet | Kasir outlet A tidak bisa melihat data outlet B (403) |
| FR-ID-03 | identity | Login JWT + refresh token | Access token 15 menit, refresh 7 hari, refresh bisa dicabut |
| FR-CT-01 | catalog | CRUD menu, kategori, harga per outlet | Harga bisa beda per outlet; default ikut harga tenant |
| FR-CT-02 | catalog | Resep (BOM) per menu: bahan + qty + satuan | Konversi satuan (kg↔g, L↔ml) benar; menu tanpa resep diberi tanda |
| FR-SL-01 | sales | Buat order, tambah item, diskon nominal/persen | Total dihitung di server; uang disimpan sebagai BIGINT Rupiah |
| FR-SL-02 | sales | Bayar tunai/QRIS (manual), cetak struk | Order PAID memicu event `SaleCompleted` tepat sekali |
| FR-SL-03 | sales | Void/refund dengan alasan + PIN manager | Tercatat di audit log; stok dikembalikan |
| FR-SL-04 | sales | Buka/tutup shift, setoran kas | Selisih kas = setoran − (modal + tunai masuk) ditampilkan |
| FR-IN-01 | inventory | Stok terpotong otomatis dari resep saat terjual | Ledger berisi baris SALE per bahan; idempoten per order |
| FR-IN-02 | inventory | Pembelian bahan (manual atau dari OCR) | Menambah stok + mencatat harga beli per satuan |
| FR-IN-03 | inventory | Stok opname per outlet | Menyimpan stok fisik; memicu `StockOpnameSubmitted` |
| FR-IN-04 | inventory | Transfer stok antar outlet | Dua baris ledger (OUT/IN) dalam satu transaksi |
| FR-IN-05 | inventory | Catat waste (basi, tumpah) | Waste tercatat tidak dihitung sebagai kebocoran |
| FR-CS-01 | costing | HPP per menu dari resep × biaya bahan | Dihitung ulang saat harga bahan berubah, < 5 detik |
| FR-CS-02 | costing | Peringatan margin di bawah target | Target default 60% (bisa diatur); daftar menu terdampak |
| FR-AI-01 | ai | Upload foto nota → draft pembelian | Manager mengoreksi sebelum disimpan; bahan dicocokkan otomatis |
| FR-AI-02 | ai | Laporan kebocoran per outlet per periode | Varians per bahan dalam % dan Rupiah + penjelasan teks |
| FR-AI-03 | ai | Command agent: perintah natural → usulan aksi | Tidak ada perubahan data tanpa klik Setujui; semua tercatat |
| FR-AI-04 | ai | Ringkasan harian setelah tutup hari | Terkirim ≤ 2 menit setelah `DayClosed` |
| FR-RP-01 | reporting | Dashboard omzet, transaksi, menu terlaris per outlet | Filter tanggal & outlet; perbandingan antar outlet |

## 5. Spesifikasi Fitur AI

Prinsip: angka dihitung secara deterministik di backend; LLM hanya menjelaskan, mengekstrak, dan menyusun usulan. LLM tidak pernah menjadi sumber angka uang.

### 5.1 Detektor Kebocoran Bahan

Dijalankan setiap kali `StockOpnameSubmitted`, untuk periode sejak opname sebelumnya.

```latex
\text{Teoritis}_b = \sum_{m} \text{qty\_terjual}_m \times \text{resep}_{m,b}
```

```latex
\text{Aktual}_b = \text{stok\_awal}_b + \text{beli}_b + \text{transfer\_in}_b - \text{transfer\_out}_b - \text{waste}_b - \text{stok\_opname}_b
```

```latex
\text{Varians}_b = \frac{\text{Aktual}_b - \text{Teoritis}_b}{\text{Teoritis}_b} \qquad \text{Rugi}_b = (\text{Aktual}_b - \text{Teoritis}_b) \times \text{biaya\_per\_unit}_b
```

- Bahan ditandai jika varians > ambang (default 5%, bisa diatur per bahan).
- Breakdown per shift dan per kasir untuk bahan yang ditandai (dari order item per shift).
- LLM menerima tabel hasil hitungan, lalu menulis 3–5 kalimat penjelasan + dugaan penyebab (porsi berlebih, resep salah, waste tidak dicatat, kehilangan).
- Output: skor kebocoran outlet dalam Rupiah, peringkat antar outlet.

### 5.2 HPP Live dari Foto Nota

1. Manager memotret nota di aplikasi → gambar ke object storage → event `ReceiptUploaded`.
2. Python: vision LLM mengekstrak `{supplier, tanggal, items:[{nama, qty, satuan, harga}]}` dengan JSON schema ketat; fallback PaddleOCR + parser jika LLM gagal.
3. Pencocokan nama ke bahan master: fuzzy match (rapidfuzz) + embedding, dengan skor keyakinan. Item di bawah 0,8 wajib dikonfirmasi manual.
4. Manager mengoreksi draft → Simpan → `PurchaseRecorded` → biaya bahan diperbarui (moving average) → `IngredientCostChanged`.
5. Costing menghitung ulang HPP menu terdampak; jika margin turun di bawah target, buat insight "Harga cabai naik 25%, margin Ayam Geprek turun ke 52%" + saran harga baru.

```latex
\text{HPP}_m = \sum_{b} \text{resep}_{m,b} \times \text{biaya\_per\_unit}_b \qquad \text{Margin}_m = \frac{\text{harga}_m - \text{HPP}_m}{\text{harga}_m}
```

### 5.3 AI Command Agent

- Owner mengetik perintah di command bar (Ctrl+K) atau panel chat.
- Python menjalankan LLM dengan tool calling. Tool read (mis. `get_sales_summary`) dijalankan langsung lewat API internal Java dengan token user.
- Tool write (mis. `update_menu_price`) tidak mengeksekusi apa pun. Tool tersebut membuat `ActionProposal` di Java berisi diff (sebelum → sesudah, jumlah baris terdampak).
- UI menampilkan diff; owner klik Setujui atau Tolak. Java mengeksekusi dengan cek RBAC, idempotency key, dan audit log.
- Batasan MVP: tidak ada tool hapus; maksimal 200 baris per aksi; proposal kedaluwarsa dalam 15 menit.

| Tool | Jenis | Contoh perintah |
| --- | --- | --- |
| `get_sales_summary` | read | "Omzet minggu ini per outlet?" |
| `get_leakage_report` | read | "Outlet mana paling boros?" |
| `get_menu_margin` | read | "Menu apa yang marginnya di bawah 50%?" |
| `update_menu_price` | write | "Naikkan es teh Rp1.000 kecuali Padang" |
| `create_menu_item` | write | "Tambah menu Kopi Aren 18 ribu" |
| `adjust_stock` | write | "Catat waste 2 kg ayam di outlet Ulak Karang" |
| `create_stock_transfer` | write | "Pindah 3 kg ayam dari A ke C" |

### 5.4 Ringkasan Harian

Setelah manager menutup hari (`DayClosed`), Python mengambil agregat (omzet, transaksi, rata-rata per transaksi, menu terlaris, jam sepi, selisih kas, perbandingan dengan rata-rata 4 minggu yang sama). LLM menulis ringkasan maksimal 120 kata + satu saran. Hasil disimpan sebagai insight; pengiriman WhatsApp masuk fase berikutnya.

## 6. Non-Functional Requirements

Target di bawah diukur di lingkungan staging dengan data seed 10 tenant × 5 outlet × 90 hari transaksi.

| Kategori | Target | Cara verifikasi |
| --- | --- | --- |
| Latensi checkout | p95 < 300 ms di API `POST /orders/{id}/pay` | k6 load test 50 kasir paralel |
| Latensi dashboard | p95 < 800 ms untuk rentang 30 hari | k6 + index review |
| AI async | Ringkasan harian ≤ 2 menit; OCR nota ≤ 30 detik | Metric `ai_job_duration_seconds` |
| AI command | Respons pertama (stream) ≤ 3 detik | Trace Java→Python |
| Offline kasir | Transaksi tetap jalan saat internet putus; sinkron otomatis tanpa duplikat | E2E Playwright dengan network offline |
| Isolasi tenant | 0 kebocoran data antar tenant | Test otomatis tiap endpoint + Postgres RLS |
| Ketersediaan | 99,5% bulanan (MVP, single region) | Uptime monitor |
| Akurasi uang | Tidak ada floating point untuk uang; pembulatan di satu tempat | Unit test + ArchUnit rule |
| Keamanan | OWASP ASVS L1; secret di env/secret manager | ZAP baseline scan di CI |
| Data pribadi | Mengikuti UU PDP: data pelanggan minimal, bisa dihapus per tenant | Endpoint export/delete tenant |
| Biaya AI | ≤ Rp15.000 per outlet per bulan pada pemakaian normal | Metric token per tenant + budget limit |

## 7. Arsitektur Sistem End-to-End

Sistem terdiri dari dua deployable: **monolith Spring Boot** sebagai satu-satunya pemilik data dan aturan bisnis, serta **AI service Python** yang stateless. Frontend React berkomunikasi hanya dengan monolith.

&#91;embedded content: arsitektur end-to-end · 2 deployable, 4 penyimpanan\]

Panah ke kanan = Java meneruskan chat agent ke ai-api; panah ke kiri = AI service memanggil API internal Java untuk menyimpan hasil OCR, insight, dan usulan aksi. AI service juga membaca Postgres lewat role read-only ber-RLS dan mengunduh foto nota dari object storage.

**Alasan modular monolith, bukan microservices:**

- Satu developer, satu database, satu transaksi untuk alur jual → potong stok; tidak perlu saga.
- Boundary tetap tegas (Spring Modulith + ArchUnit), sehingga modul bisa diekstrak menjadi service sendiri jika suatu saat diperlukan (kandidat pertama: `reporting`).
- Biaya infrastruktur cocok untuk harga langganan UMKM.

**Alur kunci (transaksi → kebocoran):**

1. Kasir bayar → `sales` menyimpan order PAID dan menerbitkan `SaleCompleted` (outbox, satu transaksi).
2. `inventory` mendengarkan, memotong stok sesuai resep ke `stock_ledger`.
3. Manager submit opname → `StockOpnameSubmitted`.
4. `ai` gateway memanggil `InventoryApi.computeLeakage()` (SQL deterministik), menyimpan hasil, menerbitkan `LeakageComputed` ke RabbitMQ.
5. ai-worker menulis penjelasan via LLM → `POST /internal/insights` → owner melihatnya di Beranda.

## 8. Struktur Modul & Boundary

Backend Java adalah satu deployable dengan 10 modul Spring Modulith. Modul hanya boleh berkomunikasi lewat API publik (interface di package root modul) atau domain event; package `internal` tidak boleh diakses modul lain. Aturan ini diverifikasi otomatis oleh `ApplicationModules.verify()` di CI.

| Modul | Tanggung jawab | Menerbitkan event | Mendengarkan event |
| --- | --- | --- | --- |
| `shared` | Money, TenantContext, error, ID (UUIDv7) | — | — |
| `identity` | Tenant, user, role, outlet scope, auth | `TenantRegistered` | — |
| `outlet` | Outlet, jam operasional, tutup hari | `DayClosed` | `TenantRegistered` |
| `catalog` | Menu, kategori, harga per outlet, resep | `MenuPriceChanged`, `RecipeChanged` | — |
| `sales` | Order, pembayaran, void, shift | `SaleCompleted`, `SaleVoided`, `ShiftClosed` | — |
| `inventory` | Bahan, satuan, stock ledger, pembelian, opname, transfer | `PurchaseRecorded`, `StockOpnameSubmitted` | `SaleCompleted`, `SaleVoided` |
| `costing` | Biaya bahan, HPP, margin, peringatan | `IngredientCostChanged`, `MarginAlertRaised` | `PurchaseRecorded`, `RecipeChanged` |
| `reporting` | Read model dashboard (proyeksi) | — | `SaleCompleted`, `ShiftClosed` |
| `ai` | Gateway ke Python, insight, ActionProposal, eksekusi aksi | `ActionExecuted` | `StockOpnameSubmitted`, `DayClosed`, `MarginAlertRaised` |
| `audit` | Audit log append-only | — | Semua event + aksi sensitif |

**Aturan dependensi:** `ai` memanggil API publik modul lain untuk eksekusi aksi (mis. `CatalogApi.updatePrice`). Modul domain tidak pernah bergantung pada `ai`. `reporting` hanya membaca dari event, tidak memanggil modul lain.

**Layout package (per modul, contoh `inventory`):**

```text
com.kasirai.inventory
├── InventoryApi.java            // API publik untuk modul lain
├── events/                      // event publik: PurchaseRecorded, ...
├── web/                         // REST controller + DTO
└── internal/
    ├── domain/                  // entity, value object, aturan bisnis
    ├── application/             // use case / service
    ├── persistence/             // Spring Data JPA repository
    └── listener/                // @ApplicationModuleListener
```

**Repo (monorepo):**

```text
kasir-ai/
├── backend/          # Java 21, Spring Boot 3, Gradle
├── ai-service/       # Python 3.12, FastAPI, uv
├── frontend/         # pnpm workspace
│   ├── apps/pos/         # PWA kasir (tablet)
│   ├── apps/backoffice/  # dashboard owner/manager
│   └── packages/ui, api-client
├── contracts/        # openapi.yaml, event JSON schema
├── infra/            # docker-compose, Caddy, Grafana
└── docs/adr/         # Architecture Decision Records
```

## 9. Data Model & Multi-Tenancy

Strategi: **satu database, shared schema, kolom `tenant_id` di setiap tabel bisnis, dijaga dua lapis**: (1) Hibernate filter otomatis dari `TenantContext`, (2) Postgres Row Level Security sebagai jaring pengaman. Schema-per-tenant tidak dipilih karena migrasi ratusan schema mahal untuk UMKM.

**Mekanisme RLS:** setiap request membuka transaksi lalu menjalankan `SET LOCAL app.tenant_id = '<uuid>'` (dari JWT). Policy: `USING (tenant_id = current_setting('app.tenant_id')::uuid)`. Role DB aplikasi bukan owner tabel, jadi RLS selalu berlaku. Service Python memakai role read-only terpisah dengan policy yang sama.

**Tabel inti** (semua punya `id UUIDv7`, `tenant_id`, `created_at`; uang = `BIGINT` Rupiah; qty = `NUMERIC(14,3)` dalam satuan dasar):

| Modul | Tabel | Kolom kunci |
| --- | --- | --- |
| identity | `tenants`, `users`, `user_outlet_roles`, `refresh_tokens` | role ∈ OWNER/MANAGER/CASHIER, outlet\_id nullable = semua outlet |
| outlet | `outlets`, `business_days` | business\_days: outlet\_id, date, status OPEN/CLOSED |
| catalog | `menu_categories`, `menu_items`, `outlet_menu_prices`, `recipe_lines` | recipe\_lines: menu\_item\_id, ingredient\_id, qty\_base |
| sales | `shifts`, `orders`, `order_items`, `payments` | orders: outlet\_id, shift\_id, status, total, client\_uuid (idempoten offline) |
| inventory | `ingredients`, `units`, `stock_ledger`, `purchases`, `purchase_lines`, `stock_opnames`, `stock_opname_lines`, `stock_transfers` | stock\_ledger: outlet\_id, ingredient\_id, qty\_delta, type, ref\_type, ref\_id |
| costing | `ingredient_costs`, `menu_cost_snapshots` | ingredient\_costs: avg\_cost\_per\_base\_unit, effective\_at |
| ai | `receipt_scans`, `ai_insights`, `action_proposals`, `ai_usage` | action\_proposals: tool, args JSONB, diff JSONB, status, expires\_at |
| audit | `audit_logs` | actor\_id, action, entity, before JSONB, after JSONB |
| infra | `event_publication` | Tabel outbox Spring Modulith |

**Keputusan penting:**

- `stock_ledger` append-only: stok saat ini = `SUM(qty_delta)`, di-cache di tabel `stock_balances` yang diperbarui di transaksi yang sama.
- Index utama: `(tenant_id, outlet_id, created_at)` untuk orders dan ledger; partisi bulanan untuk `stock_ledger` dan `orders` setelah > 10 juta baris.
- Order dari kasir offline membawa `client_uuid`; unique constraint `(tenant_id, client_uuid)` mencegah duplikat saat sinkron.
- Migrasi dengan Flyway; satu folder migrasi per modul (`db/migration/inventory/...`).

## 10. AI Service (Python)

AI service adalah deployable kedua yang stateless: tidak punya database sendiri, tidak menulis ke database utama, dan hanya mengubah data lewat API Java. Dipisah dari monolith karena ekosistem AI/ML ada di Python, bukan karena alasan domain.

**Stack:** Python 3.12, FastAPI, Pydantic v2, uv, SQLAlchemy Core (read-only), pandas, rapidfuzz, aio-pika (RabbitMQ), httpx, OpenTelemetry. LLM lewat satu interface `LLMProvider` (Claude API atau OpenAI) agar mudah diganti.

**Struktur:**

```text
ai-service/app/
├── api/            # endpoint sync: /agent/chat (SSE), /health
├── workers/        # consumer RabbitMQ: receipt, leakage, daily_summary
├── agent/          # tool registry, prompt, guardrail, loop tool calling
├── pipelines/      # ocr_receipt.py, leakage_explain.py, daily_summary.py
├── data/           # query read-only dengan SET LOCAL app.tenant_id
├── clients/        # java_api.py (internal API), storage.py, llm/
└── evals/          # golden set + skrip evaluasi
```

**Pipeline:**

| Pipeline | Pemicu | Langkah | Output |
| --- | --- | --- | --- |
| OCR nota | `ReceiptUploaded` (queue) | Unduh gambar → vision LLM + JSON schema → fallback PaddleOCR → fuzzy match bahan | `POST /internal/receipt-scans/{id}/result` |
| Penjelasan kebocoran | `LeakageComputed` (queue) | Terima tabel varians dari Java → LLM menulis penjelasan | `POST /internal/insights` |
| Ringkasan harian | `DayClosed` (queue) | Query agregat read-only → bandingkan 4 minggu → LLM menulis | `POST /internal/insights` |
| Command agent | HTTP SSE dari FE via Java | Loop tool calling maks 6 langkah; tool write → buat proposal | Stream teks + `proposal_id` |

**Catatan desain:** perhitungan varians kebocoran dilakukan di Java (modul `inventory` + `costing`) dalam SQL, karena itu angka uang yang harus konsisten dan teruji. Python hanya menjelaskan.

**Guardrail agent:**

- System prompt berisi daftar outlet dan menu milik tenant (bukan data tenant lain).
- Argumen tool divalidasi Pydantic sebelum dikirim; Java memvalidasi ulang + cek RBAC.
- Prompt injection dari data (mis. nama menu berisi instruksi) diperlakukan sebagai data; tool write tetap butuh approval manusia.
- Budget token per tenant per hari; jika habis, fitur AI menampilkan pesan, POS tetap jalan.

## 11. Kontrak API & Domain Event

API didesain contract-first di `contracts/openapi.yaml`; client TypeScript di-generate dengan `openapi-typescript`. Tenant tidak pernah ada di URL; selalu dari JWT. Semua endpoint tulis menerima header `Idempotency-Key`.

**API publik (`/api/v1`, dipakai frontend):**

| Method & path | Fungsi | Role |
| --- | --- | --- |
| `POST /auth/register` · `/auth/login` · `/auth/refresh` | Registrasi tenant, login, refresh | publik |
| `GET/POST /outlets` · `POST /outlets/{id}/close-day` | Kelola outlet, tutup hari | Owner / Manager |
| `GET/POST/PATCH /menu-items` · `PUT /menu-items/{id}/recipe` | Menu dan resep | Owner |
| `POST /orders` · `POST /orders/{id}/items` · `POST /orders/{id}/pay` | Transaksi kasir | Kasir+ |
| `POST /orders/sync` | Kirim batch order offline (maks 100) | Kasir+ |
| `POST /shifts/open` · `POST /shifts/{id}/close` | Shift | Kasir+ |
| `GET /ingredients` · `POST /purchases` · `POST /stock-opnames` · `POST /stock-transfers` | Inventori | Manager+ |
| `POST /receipt-scans` (multipart) · `GET /receipt-scans/{id}` | Upload nota, ambil draft hasil OCR | Manager+ |
| `GET /costing/menu-margins` | HPP & margin per menu | Owner |
| `GET /reports/leakage?outletId&from&to` | Laporan kebocoran | Owner / Manager |
| `GET /insights` | Ringkasan harian & peringatan | Owner / Manager |
| `POST /ai/chat` (SSE) | Command agent (Java proxy ke Python) | Owner / Manager |
| `POST /ai/proposals/{id}/approve` · `/reject` | Approval aksi AI | Owner / Manager |

**API internal (`/internal/v1`, hanya untuk ai-service):** dilindungi service token + mTLS di jaringan privat. Read tool memanggil API publik dengan token user yang diteruskan (on-behalf-of), sehingga RBAC tetap berlaku. Endpoint khusus internal: `POST /internal/receipt-scans/{id}/result`, `POST /internal/insights`, `POST /internal/proposals`.

**Domain event** (dalam proses via Spring Modulith; yang bertanda → juga diterbitkan ke RabbitMQ exchange `kasir.events`):

| Event | Penerbit | Payload inti | Konsumen |
| --- | --- | --- | --- |
| `SaleCompleted` | sales | orderId, outletId, items\[menuId, qty\], total | inventory, reporting, audit |
| `SaleVoided` | sales | orderId, reason, actorId | inventory, audit |
| `ShiftClosed` | sales | shiftId, expectedCash, actualCash | reporting, audit |
| `PurchaseRecorded` | inventory | purchaseId, lines\[ingredientId, qty, unitCost\] | costing |
| `StockOpnameSubmitted` | inventory | opnameId, outletId, periodStart, periodEnd | ai (memicu hitung kebocoran) |
| `IngredientCostChanged` | costing | ingredientId, oldCost, newCost | costing (HPP), ai |
| `MarginAlertRaised` | costing | menuIds\[\], marginPct | ai |
| `LeakageComputed` → | ai | outletId, period, rows\[ingredient, variancePct, lossRp\] | ai-service |
| `ReceiptUploaded` → | ai | scanId, objectKey | ai-service |
| `DayClosed` → | outlet | outletId, date | ai-service |
| `ActionExecuted` | ai | proposalId, tool, affectedIds | audit |

Setiap event membawa envelope `{eventId, type, version, tenantId, occurredAt, payload}`. Reliabilitas: Event Publication Registry (outbox) Spring Modulith menjamin at-least-once; konsumen idempoten berdasarkan `eventId`.

## 12. Frontend & Desain UI

Ada dua aplikasi dengan tujuan desain berbeda: **POS** harus cepat disentuh saat ramai, sedangkan **Backoffice** harus langsung menunjukkan "berapa rupiah yang bocor dan apa tindakannya". Keduanya berbagi satu design system.

### 12.1 Stack Frontend

| Kebutuhan | Pilihan |
| --- | --- |
| Build & bahasa | Vite + React 18 + TypeScript strict |
| Routing | TanStack Router (type-safe) |
| Server state | TanStack Query + client hasil generate OpenAPI |
| Client state | Zustand (keranjang, sesi kasir) |
| Form & validasi | react-hook-form + zod |
| UI | Tailwind CSS + shadcn/ui (Radix), ikon Lucide |
| Chart | Recharts |
| Offline POS | PWA (vite-plugin-pwa) + IndexedDB via Dexie, antrean sinkron |
| Test | Vitest + Testing Library, Playwright untuk E2E |

### 12.2 Desain POS (tablet landscape, 10–11 inci)

- **Layout tiga kolom:** kategori (kiri, sempit) · grid menu dengan foto/nama/harga (tengah) · keranjang + total + tombol Bayar besar (kanan).
- Target sentuh minimal 48 px; satu ketuk = tambah item; tahan = pilih varian/catatan.
- Pencarian menu dengan keyboard on-screen; menu yang stok bahannya habis tampil redup.
- Indikator koneksi di header: hijau online, kuning "offline, 12 transaksi menunggu sinkron".
- Layar bayar: nominal cepat (pas, 50rb, 100rb), kembalian besar dan jelas.
- Tutup shift: form setoran kas dengan selisih yang langsung terlihat.

### 12.3 Desain Backoffice (desktop + responsif untuk HP owner)

- **Sidebar** kiri + **outlet switcher** di atas ("Semua outlet" atau satu outlet).
- **Beranda = kartu kebocoran**, bukan grafik omzet: "Minggu ini bahan bocor Rp1,2 jt — Outlet Ulak Karang 68%", lalu daftar 3 tindakan teratas.
- Halaman **Kebocoran**: tabel bahan per outlet dengan varians %, rugi Rp, dan teks penjelasan AI di panel samping.
- Halaman **Margin Menu**: tabel menu dengan HPP, harga, margin; baris di bawah target berwarna amber, ada tombol "Terapkan harga saran".
- Halaman **Scan Nota**: gambar nota di kiri, draft item di kanan; item dengan keyakinan rendah ditandai untuk dicek.
- **AI Command Bar** (Ctrl+K atau tombol mengambang di HP): chat dengan streaming; setiap usulan aksi tampil sebagai kartu diff (sebelum → sesudah, jumlah outlet terdampak) dengan tombol Setujui/Tolak.

### 12.4 Design Token

- Font Inter; angka memakai `tabular-nums` agar kolom Rupiah rata.
- Warna semantik: hijau = sehat, amber = perlu dicek, merah = kebocoran/rugi; satu warna aksen brand untuk aksi utama. Warna tidak pernah menjadi satu-satunya penanda (selalu ada ikon atau teks).
- Format uang `Rp1.250.000`, singkatan di kartu (`Rp1,2 jt`); tanggal `20 Nov 2026`.
- Mode terang default; mode gelap untuk kasir malam (opsional).

**Layar prioritas untuk dibuat mockup:** POS checkout, Beranda owner, Kebocoran, Margin Menu, Scan Nota, AI Command Bar + kartu diff.

## 13. Security, RBAC, Audit & Observability

**Autentikasi:** Spring Security, password hash Argon2id, JWT access token (15 menit, RS256) + refresh token rotasi (7 hari, disimpan hash di DB, httpOnly cookie untuk backoffice). PIN 6 digit untuk otorisasi manager di POS (void, refund, diskon besar).

**RBAC:** izin dicek di layer aplikasi dengan `@PreAuthorize` + evaluator outlet scope.

| Aksi | Owner | Manager | Kasir |
| --- | --- | --- | --- |
| Transaksi & shift | Ya | Ya | Ya (outlet sendiri) |
| Void / refund | Ya | Ya | Dengan PIN manager |
| Pembelian, opname, scan nota | Ya | Ya (outlet sendiri) | Tidak |
| Menu, harga, resep | Ya | Tidak | Tidak |
| Laporan kebocoran & margin | Semua outlet | Outlet sendiri | Tidak |
| AI Command Agent | Semua tool | Tool read + inventori outlet sendiri | Tidak |
| Kelola user & tenant | Ya | Tidak | Tidak |

**Audit log:** append-only (tanpa UPDATE/DELETE di level grant DB) untuk void, refund, ubah harga, ubah resep, penyesuaian stok, login gagal, dan setiap `ActionProposal` (siapa yang meminta, prompt, diff, siapa yang menyetujui).

**Proteksi lain:** rate limit per user/tenant (Bucket4j + Redis), validasi upload (tipe gambar, maks 5 MB, strip EXIF), CORS ketat, header keamanan via Caddy, secret lewat environment, dependency scan (Dependabot + Trivy untuk image).

**Observability:**

- Log JSON terstruktur dengan `traceId`, `tenantId`, `outletId` (tanpa data pribadi).
- OpenTelemetry trace end-to-end: FE → Java → RabbitMQ → Python → LLM.
- Metric Micrometer/Prometheus: latensi checkout, event gagal di outbox, durasi job AI, token LLM per tenant, akurasi OCR (koreksi manual / total item).
- Dashboard Grafana + alert: outbox tertunda > 5 menit, error rate > 2%, budget token > 80%.
- Sentry untuk error frontend.

## 14. Infrastruktur, Deployment & CI/CD

MVP berjalan di satu VPS (4 vCPU, 8 GB RAM) dengan Docker Compose; arsitektur tetap siap dipecah ke beberapa host tanpa ubah kode.

| Komponen | Container / layanan | Catatan |
| --- | --- | --- |
| Reverse proxy | Caddy | HTTPS otomatis; sajikan build statis FE |
| Backend | `backend` (Spring Boot, JRE 21) | 1–2 replika; heap 1,5 GB |
| AI service | `ai-api` (FastAPI) + `ai-worker` (consumer) | Image sama, command berbeda |
| Database | PostgreSQL 16 | Backup harian `pg_dump` + WAL ke object storage |
| Cache & rate limit | Redis 7 |  |
| Message broker | RabbitMQ 3.13 | Dead-letter queue untuk job AI gagal |
| Object storage | MinIO (lokal) / Cloudflare R2 (prod) | Foto nota; URL presigned |
| Monitoring | Prometheus, Grafana, Loki, Tempo | Bisa diganti Grafana Cloud free tier |

**Lingkungan:** `local` (docker compose + seed data), `staging` (deploy otomatis dari `main`), `prod` (deploy dari tag `v*` dengan approval manual).

**Pipeline GitHub Actions:**

1. Lint & format: Spotless (Java), Ruff + mypy (Python), ESLint + tsc (FE).
2. Test: JUnit + Testcontainers, pytest, Vitest.
3. Verifikasi arsitektur: `ApplicationModules.verify()` + ArchUnit rules.
4. Cek kontrak: OpenAPI diff (breaking change = gagal) + regenerasi client FE.
5. Build image → push ke GHCR → scan Trivy.
6. Deploy staging via SSH + `docker compose pull && up -d`; smoke test Playwright.
7. Migrasi DB Flyway berjalan saat startup backend (staging otomatis, prod dengan approval).

## 15. Strategi Testing

Fokus test ada pada tiga hal yang paling mahal jika salah: angka uang, isolasi tenant, dan aksi AI.

| Level | Alat | Cakupan wajib |
| --- | --- | --- |
| Unit | JUnit 5 + AssertJ, pytest, Vitest | Rumus HPP, varians, konversi satuan, pembulatan Rupiah (property-based dengan jqwik) |
| Module test | `@ApplicationModuleTest` (Spring Modulith) | Tiap modul diuji terisolasi; event yang diterbitkan diverifikasi dengan `Scenario` |
| Integration | Testcontainers (Postgres, RabbitMQ, Redis) | Alur jual → ledger → opname → kebocoran dengan data seed |
| Tenant isolation | Test parametrik per endpoint | User tenant A memanggil resource tenant B → 404; query RLS tanpa `app.tenant_id` → 0 baris |
| Contract | OpenAPI diff + schemathesis | Respons API sesuai kontrak; event cocok dengan JSON schema |
| AI eval | Golden set di `ai-service/evals` | 40 perintah agent (tool & argumen benar ≥ 90%), 30 foto nota (akurasi item ≥ 85%) |
| E2E | Playwright | Checkout online & offline, scan nota, approve aksi AI |
| Load | k6 | Target NFR checkout dan dashboard |

AI eval dijalankan di CI hanya saat folder `agent/`, `pipelines/`, atau prompt berubah, untuk menghemat biaya token.

## 16. Roadmap & Milestone

MVP selesai dalam 8 minggu kerja untuk satu developer, dengan fitur AI di minggu 6–7 setelah data penjualan dan stok sudah benar. Setiap fase baru dimulai setelah gate fase sebelumnya lulus.

&#91;embedded content: roadmap MVP · 5 fase, 8 minggu\]

Gate M2 berarti hasil hitung kebocoran sistem sama dengan hitungan manual di spreadsheet untuk data seed. Jika waktu mepet, ringkasan harian dan mode offline (Should) dipindah ke setelah M4, bukan memotong gate.

## 17. Metrik Sukses, Risiko & Open Questions

**Metrik sukses MVP:**

| Metrik | Target | Kapan diukur |
| --- | --- | --- |
| Alur demo end-to-end | Berjalan tanpa intervensi manual, < 5 menit | Akhir M4 |
| Kebocoran terdeteksi di pilot | ≥ 1 temuan yang dikonfirmasi owner per outlet | 4 minggu pilot |
| Akurasi OCR nota | ≥ 85% item benar tanpa koreksi | Golden set + pilot |
| Agent memilih tool benar | ≥ 90% di golden set | CI |
| Owner membuka insight | ≥ 4 dari 7 hari | Pilot |

**Risiko & mitigasi:**

| Risiko | Dampak | Mitigasi |
| --- | --- | --- |
| Resep tidak diisi akurat oleh UMKM | Kebocoran palsu, owner tidak percaya | Wizard resep + template menu umum; tandai "resep belum diverifikasi" |
| Opname jarang dilakukan | Laporan kebocoran kosong | Opname parsial (5 bahan termahal) mingguan |
| Nota tulisan tangan sulit dibaca | Data harga salah | Wajib konfirmasi manual untuk skor < 0,8 |
| LLM salah memahami perintah | Perubahan data salah | Approval wajib + diff + batas 200 baris + audit |
| Biaya LLM membengkak | Margin SaaS turun | Budget per tenant, cache, model kecil untuk tugas sederhana |
| Scope terlalu besar untuk satu orang | MVP tidak selesai | Kunci scope Must; Should/Could setelah demo |

**Open questions:**

- [ ] Provider LLM utama: Claude API atau OpenAI? (pengaruh ke biaya & kualitas vision OCR)
- [ ] Metode biaya bahan: moving average (diusulkan) atau harga beli terakhir?
- [ ] Pilot pertama: outlet siapa, di kota mana?
- [ ] WhatsApp: WA Business Cloud API resmi atau penyedia lokal?
- [ ] Nama produk dan domain.

## Lampiran A. Paket Langganan dan Aturan Bisnis yang Tetap Berlaku (dari PRD v2.2)

### A.1 Paket Langganan dan Kuota AI
Pembatasan dilakukan lewat fitur, bukan jumlah transaksi. Transaksi dan data tidak dibatasi di semua paket.

| | Gratis | Pro ("Paling populer") | Premium |
|---|---|---|---|
| Harga bulanan | Rp0 | Rp59.000 | Rp119.000 |
| Harga tahunan ("gratis 2 bulan") | — | Rp590.000 | Rp1.190.000 |
| Outlet | 1 | 1 + add-on | 1 + add-on |
| Pengguna | Owner saja (owner sebagai kasir) | Owner + kasir tanpa batas | Owner + kasir tanpa batas |
| POS, menu, resep, stok, struk, shift & kas kecil | Ya | Ya | Ya |
| Undang kasir, kelola staf, void/refund, audit kasir | Tidak | Ya | Ya |
| Laporan | Ringkasan & produk terlaris, riwayat 30 hari | Lengkap (per jam/kasir/shift, HPP & laba, CSV), riwayat penuh | Lengkap |
| Asisten AI Telegram | Tidak | Tidak | Ya: ringkasan harian (tidak memotong kuota) + 300 pertanyaan/bulan |
| Top-up AI | — | — | +200 pertanyaan Rp29.000, berlaku sampai akhir periode |
| Outlet tambahan | — | +Rp49.000/outlet/bulan (aktivasi manual operator, TASK-083) | sama |

- Premium mencakup seluruh isi Pro.
- Kuota AI dihitung per pertanyaan, bukan kredit/token. Owner diberi tahu pada 80% dan 100% kuota.
- Batas harian anti-penyalahgunaan: 40 pertanyaan per tenant per hari.
- Secara internal, pemakaian token per tenant dicatat dari field `usage` respons LLM. Satu pertanyaan maksimal 4 panggilan LLM.
- Model default: DeepSeek V4.1 Flash via OpenRouter (env `AI_MODEL`). Anggaran biaya Rp100 per pertanyaan. Batas atas dari kode: prompt 21.000 byte dan output 1.024 token per panggilan, sekitar Rp165 per pertanyaan.
- Trial Premium 14 hari otomatis setelah 50 transaksi pertama di paket Gratis, satu kali per tenant.
- Pemicu upgrade di UI: tombol "Undang kasir" di Gratis menampilkan tawaran Pro (bukan error); dashboard Gratis menampilkan kartu sekilas temuan AI yang terkunci.
- Paket turun atau habis: data tetap utuh, tidak pernah dihapus.
  - Kasir dinonaktifkan sampai paket kembali Pro.
  - Bot AI menolak dengan pesan perpanjang.
- Pembayaran langganan saat pilot: transfer manual dan aktivasi oleh SUPERADMIN. Integrasi tagihan otomatis menyusul.

### A.2 Aturan Bisnis

- Harga, nama item, dan opsi disalin sebagai snapshot; total dihitung server; uang memakai `BigDecimal`.
- Tarif pajak dan biaya layanan disnapshot pada saat pesanan dibuat.
- Pesanan lunas tidak dapat dihapus; koreksi hanya lewat void/refund oleh owner.
- Diskon hanya oleh owner, sekali per pesanan, sebelum pajak/layanan, tidak melebihi subtotal.
- Pembayaran dan pengeluaran kas kecil membutuhkan shift terbuka milik penerima/pencatatnya.
- Aplikasi tidak memproses uang; setiap pembayaran adalah pencatatan oleh kasir.
- Semua waktu disimpan dalam UTC dan ditampilkan menurut zona waktu outlet.
- Tenant ditentukan dari JWT yang tervalidasi, tidak pernah dari payload; tidak ada bukti keberadaan data tenant lain.
- Aksi uang dan aksi sensitif mencatat pelaku, waktu, dan alasan.
- Stok boleh minus; sistem memberi peringatan, tidak memblokir penjualan.
