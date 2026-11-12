# Prompt untuk agent frontend: integrasi REST API F&B KedaiMbo

Kamu bekerja di repo frontend `E:\Java\cashier-saas-ui` (React 19, Vite 8, TypeScript 6, Tailwind 4, TanStack Query 5, react-hook-form + zod 4, axios). Backend ada di `E:\Java\cashier-saas` (Spring Boot, `/api/v1`). **Jangan mengubah repo backend** (read-only untuk kamu). Tugasmu: hubungkan UI ke REST API backend yang sudah jadi, menggantikan mock di `src/api/mock/*`.

## Mulai dari sini (baca dulu, jangan menebak kontrak)
1. `E:\Java\cashier-saas-ui\AGENTS.md`/`GEMINI.md`/`ARCHITECTURE.md` bila ada, lalu `src/api/client.ts`, `src/api/response.ts`, `src/api/product.api.ts`, `inventory.api.ts`, `fnbOrder.api.ts`, dan mock-nya.
2. Kontrak backend: DTO di `E:\Java\cashier-saas\src\main\java\org\lite\project\cashiersaas\dto\request|response\Fnb*.java` dan controller `controller\Fnb*Controller.java`. Docs: `E:\Java\cashier-saas\docs\fnb-pay-first-backend.md`, `docs\fnb-operations.md`, `docs\fnb-realtime.md`. Contoh request: koleksi Postman `E:\Java\cashier-saas\postman\collections\KedaiMbo API` (SEBAGIAN USANG: folder "08 - F&B Option Groups" masih memakai route lama sebelum V5; percayai controller, bukan Postman).
3. Bentuk respons: semua `ApiResponse<T>` = `{ success, code, message, data, validationErrors[{field,message}], timestamp }`. Pesan dalam bahasa Indonesia. Uang dikirim sebagai angka/desimal (BigDecimal), tampilkan lewat `utils/formatCurrency.ts`.

## Prefix dan role
- `/api/v1/merchant/...` = hanya OWNERMERCHANT. `/api/v1/cashier/...` = OWNERMERCHANT dan CASHIER. `/api/v1/public/...` = tanpa login (QR).
- Auth: Bearer JWT. **Tenant datang dari token, jangan pernah mengirim tenantId di body/header.** `{outletId}` ada di path.
- Role dan tenant tidak boleh diputuskan di UI saja: UI hanya menyembunyikan menu, backend yang menegakkan (403/404).

## Endpoint yang dicakup (prefix `/api/v1`, `{o}` = outletId)
Katalog (`FnbCatalogController`):
- Kategori: `POST|PUT|DELETE /merchant/outlets/{o}/fnb/categories[/{id}]`, `GET /cashier/outlets/{o}/fnb/categories`
- Produk: `POST|PUT|DELETE /merchant/outlets/{o}/fnb/products[/{id}]`, `GET /cashier/outlets/{o}/fnb/products`
- Option group **reusable di level outlet** (sudah pindah dari route per-produk): `POST|PUT|DELETE /merchant/outlets/{o}/fnb/option-groups[/{id}]`, `GET /cashier/outlets/{o}/fnb/option-groups`; opsi: `POST /merchant/.../option-groups/{groupId}/options`, `PUT .../options/{id}`, `GET /cashier/.../option-groups/{groupId}/options`
- Lampirkan group ke produk: `PUT /merchant/.../products/{productId}/option-groups` (body `FnbProductOptionGroupsRequest`), `GET /cashier/.../products/{productId}/option-groups`
- Resep: `PUT|GET /merchant/.../products/{productId}/recipe`, `PUT|GET /merchant/.../option-groups/{groupId}/options/{id}/recipe`

Inventori (`FnbInventoryController`):
- Item: `POST|PUT|DELETE /merchant/outlets/{o}/fnb/inventory/items[/{id}]`, `GET /cashier/.../inventory/items`
- Gerakan stok: `POST|GET /cashier/outlets/{o}/fnb/inventory/items/{id}/movements`. **POST wajib idempotency key** (lihat `FnbStockMovementRequest`/header yang dipakai controller); buat key baru per aksi pengguna, pakai ulang key yang sama hanya untuk retry request yang persis sama.

Pesanan dan pembayaran (boleh pada tahap berikutnya, urutan prioritas ada di bawah): `FnbStaffOrderController` (POS order + list + detail), `FnbPaymentController` (bayar, resolve mock payment, cancel), `FnbKitchenController`, `FnbDiscountController` (OWNER saja), `FnbReceiptController`, `FnbTableController`, `FnbOrderQrController`, `FnbSalesReportController`, `FnbRealtimeController` (ticket realtime, polling sebagai fallback).

## Aturan perilaku yang harus diikuti UI
- **Pay first**: menu -> (meja/QR opsional) -> order -> bayar -> dapur -> selesai -> laporan. Jangan membuat alur yang melewati pembayaran.
- Total dihitung server. UI hanya menampilkan, jangan menghitung ulang total sebagai sumber kebenaran.
- Checkout/bayar wajib idempotency key. Kegagalan jaringan -> retry dengan key yang sama; double-click tombol bayar harus dicegah (disable saat pending).
- Stok boleh minus; pembayaran bisa sukses dengan `stockWarnings`. Tampilkan sebagai peringatan, bukan error.
- Pesanan yang sudah dibayar tidak bisa dibatalkan (400). Pembatalan wajib alasan.
- Waktu dari server dirender dengan zona waktu outlet.

## Penanganan error (centralized di axios interceptor / `response.ts`)
- 400: `validationErrors` -> petakan ke field react-hook-form; jika tanpa field -> toast `message`.
- 401: bersihkan sesi, ke halaman login. 403: tampilkan "tidak punya akses". 404: perlakukan sebagai "tidak ditemukan" (backend sengaja tidak membedakan data tenant lain).
- **409 = konflik, bisa di-retry**: (a) race optimistic lock, pesan "Data baru saja diubah oleh proses lain. Silakan coba lagi." (baru ditambahkan TASK-071) -> refetch data terkait lalu tawarkan "Coba lagi", jangan otomatis mengulang aksi tanpa konfirmasi untuk operasi yang mengubah uang; (b) idempotency key dipakai ulang dengan payload berbeda -> ini bug klien, buat key baru; (c) duplikasi data.
- **429 = terlalu banyak permintaan (backend SUDAH menerapkannya, kontrak final di bagian "Rate limiting")**: baca header `Retry-After` (detik, bilangan bulat), tampilkan `message` dari body, nonaktifkan aksi sampai waktu itu, jangan retry otomatis agresif. Query GET boleh backoff eksponensial dengan batas.
- 5xx: pesan umum, jangan tampilkan detail. Jangan pernah log token.

## Rate limiting: kontrak FINAL (backend sudah jadi, commit `0f39c5f`)
Respons saat kena batas:
- HTTP status `429`.
- Header `Retry-After: <detik>` (bilangan bulat >= 1). Header ini sudah masuk `Access-Control-Expose-Headers`, jadi bisa dibaca dari JS (`error.response.headers["retry-after"]`).
- Body `ApiResponse`: `{ "success": false, "code": 429, "message": "Terlalu banyak permintaan. Coba lagi dalam N detik.", ... }`. Tampilkan `message` apa adanya.
- Tidak ada header lain (jangan menunggu `X-RateLimit-*`, tidak ada).

Batas default (bisa diubah operator, jangan di-hardcode di UI sebagai angka pasti; hanya sebagai pedoman perilaku):
- Login/register/refresh: 10 per menit per IP. Menu dan status order publik (QR): 240 per menit per IP. Buat order publik: 20 per menit per IP.
- Request yang sudah login (`/merchant/**`, `/cashier/**`): 300 per menit per user (burst 60). Ticket realtime: 10 per menit per user.
- Konsekuensi untuk UI: (1) debounce input pencarian dan nonaktifkan tombol aksi saat pending, (2) polling tidak lebih rapat dari 5 detik (12 per menit sudah aman), (3) jangan memanggil ulang ticket realtime kecuali koneksi putus, (4) TanStack Query: `retry` jangan mengulang 4xx, untuk 429 tunggu `Retry-After` baru coba lagi, (5) satu handler 429 terpusat di interceptor axios yang sudah ada (jangan di setiap halaman).

## ATURAN UTAMA: jangan rewrite, cek dulu, tidak duplikat, tidak tight coupling
Repo frontend ini SUDAH berisi kode (`src/api/*.api.ts`, mock, hooks, schema, halaman). Tugasmu menyambungkan, bukan membangun ulang.
1. **Cek sebelum menulis.** Untuk setiap fungsi, tipe, hook, schema, komponen atau util yang akan kamu buat: cari dulu di repo (Grep/Glob nama dan kata kuncinya di `src/api`, `src/hooks`, `src/schema`, `src/types`, `src/utils`, `src/components`). Jika sudah ada dan cukup, PAKAI. Jika hampir cukup, perluas secukupnya. Buat baru hanya jika terbukti belum ada, dan sebutkan di laporan hasil pencarianmu.
2. **Dilarang rewrite.** Jangan menulis ulang, memindahkan, merename, atau memformat ulang file yang sudah berfungsi. Ubah hanya baris yang perlu untuk tugas ini; diff harus kecil dan bisa dibaca. Jangan mengubah gaya, struktur folder, nama ekspor publik, atau komponen UI yang tidak berhubungan. Jangan menghapus mock yang masih dipakai.
3. **Dilarang duplikat.** Satu endpoint dipanggil dari satu fungsi di `*.api.ts`; satu tipe respons didefinisikan satu kali; logika error/429/409 hanya di interceptor/`response.ts`, bukan disalin ke tiap halaman atau hook. Jangan membuat file baru yang kembar fungsi dengan file lama (mis. `inventory2.api.ts`, `productService.ts` di samping `product.api.ts`).
4. **Dilarang tight coupling.** Halaman/komponen tidak boleh memanggil axios atau menulis URL langsung; mereka memakai hook TanStack Query yang memanggil fungsi di `*.api.ts`. Tipe backend tidak bocor ke komponen presentasi tanpa lewat tipe di `src/types`. Tidak ada dependensi melingkar antar modul, tidak ada state global baru jika TanStack Query sudah cukup, tidak ada konstanta/URL/angka sihir yang diulang (pakai `src/constants`/`src/config` yang ada).
5. **Seringan mungkin.** Tidak ada library baru, abstraksi cadangan, wrapper generik, HOC, atau lapisan "untuk nanti". Jangan menambah fitur di luar daftar endpoint di atas. Jika dua opsi sama benarnya, pilih yang menambah kode paling sedikit.
6. **Sebelum selesai, audit dirimu sendiri**: jalankan pencarian untuk memastikan tidak ada fungsi/tipe ganda yang baru kamu buat, tidak ada `any` baru, tidak ada import yang tidak terpakai, dan typecheck + oxlint bersih. Di laporan akhir, tulis bagian "Yang sudah ada dan dipakai ulang" (daftar file/fungsi) dan "Yang baru dibuat dan alasannya".

Jika kamu sudah terlanjur mengerjakan sebagian sebelum membaca aturan ini: berhenti, periksa `git status`/`git diff` di `E:\Java\cashier-saas-ui`, kembalikan HANYA perubahanmu sendiri yang melanggar aturan di atas (rewrite besar, file duplikat), lalu lanjutkan dengan diff yang kecil. Jangan menyentuh perubahan orang lain yang tidak kamu buat.

## Cara kerja dan batasan
- Ganti implementasi mock dengan pemanggilan nyata lewat `src/api/client.ts`; pertahankan nama fungsi/tipe publik di `*.api.ts` bila memungkinkan supaya halaman tidak banyak berubah. Simpan mock hanya bila masih dipakai test/demo; sakelar lewat env (mis. `VITE_USE_MOCK`) jika sudah ada polanya, jangan membuat mekanisme baru bila sudah ada.
- Tipe TypeScript harus cocok dengan DTO backend (nama field persis). Validasi form dengan zod di `src/schema`; pesan Indonesia; token warna dan komponen `components/ui/*` yang sudah ada (KedaiMbo, Tailwind 4), jangan menambah library baru tanpa alasan.
- Urutan kerja: (1) client + error handling + tipe, (2) kategori/produk/option group reusable/lampir group, (3) inventori + resep + gerakan stok, (4) order/bayar/dapur, (5) laporan dan realtime. Selesaikan dan laporkan per tahap.
- Verifikasi: `pnpm` typecheck dan lint (oxlint) harus bersih; jalankan vitest hanya untuk file yang kamu sentuh. Uji manual terhadap backend lokal bila bisa (profile demo, lihat `E:\Java\cashier-saas\docs\demo.md`); jika tidak bisa dijalankan, katakan jujur bahwa integrasi belum diuji ke server nyata.
- Jangan commit/push. Jangan mengubah `E:\Java\cashier-saas`. Laporkan di akhir: file yang berubah, endpoint yang sudah terhubung, yang belum, dan ketidakcocokan kontrak yang kamu temukan (jangan diam-diam menyesuaikan UI bila menurutmu backend yang salah; laporkan).
