# PROMPT UNTUK STITCH WITH GOOGLE (AGY) — UI MANAJEMEN PRODUK & STOK BAHAN

Produk: **KedaiMbo** (POS SaaS untuk F&B/UMKM Indonesia) · Modul: **Manajemen Produk, Varian/Tambahan, Resep, dan Stok Bahan**
Sumber kebenaran backend: `tasks/TASK-061.md` (kontrak inventory), controller `FnbCatalogController` (katalog). Frontend repo: `E:\Java\cashier-saas-ui` (React 19 + Tailwind 4 + Radix).

## Cara pakai

1. Buka Stitch, pilih **desktop (1440 px)** sebagai frame utama. Setelah selesai, minta Stitch membuat varian **tablet (834 px)** dan **mobile (390 px)** dari frame yang sama.
2. Tempel **BAGIAN A (Master Context)** lebih dulu. Itu aturan desain, bahasa, dan token yang berlaku untuk semua layar.
3. Tempel **BAGIAN B** satu per satu (B1 → B8). Jangan digabung dalam satu prompt supaya hasil tiap layar tidak dangkal.
4. Setelah semua layar jadi, jalankan **BAGIAN C (prompt perbaikan)** untuk konsistensi, lalu periksa **BAGIAN D (checklist)**.
5. Seluruh teks di dalam UI harus **Bahasa Indonesia**. Prompt-nya ditulis dalam bahasa Inggris karena Stitch lebih akurat dengan itu.

---

## BAGIAN A — MASTER CONTEXT (tempel pertama)

```
You are designing the "Produk & Stok" (Product & Stock management) area of KedaiMbo, a calm, warm, trustworthy
point-of-sale SaaS for small Indonesian food & beverage businesses (cafes, kedai, warung). The users are
shop owners (role OWNERMERCHANT) and cashiers (role CASHIER). Most of the work happens on a tablet or laptop at
the counter, sometimes on a phone. The mood is "Calm Commerce": warm cream canvas, olive green brand, soft
bronze accents, generous whitespace, rounded cards, no harsh blue, no neon, no heavy gradients.

LANGUAGE: every label, button, placeholder, empty state, toast and error message is in natural Indonesian
(e.g. "Tambah Produk", "Simpan", "Batal", "Stok Bahan", "Menipis"). Numbers and currency use Indonesian format:
"Rp25.000", "1.250 g", "-20 ml". Never use English UI text or lorem ipsum; use realistic Indonesian F&B sample
data (Kopi Susu Gula Aren, Matcha Latte, Nasi Goreng Kampung, Croissant Butter, Biji Kopi, Susu, Boba, Oat Milk).

DESIGN SYSTEM (use exactly these tokens; Material-3 style roles):
- Font: "Plus Jakarta Sans". Icons: Material Symbols Outlined (rounded, weight 400).
- Light mode: canvas/background #fbf9f4, cards #ffffff, surface tiers #f5f3ee / #f0eee9 / #eae8e3 / #e4e2dd,
  text #1b1c19, secondary text #655e51, borders #e4e2dd (strong outline #76786d).
- Primary (warm olive): #53613c, hover #455132, active #3b462a, container #6b7a53, on-primary #ffffff,
  tint/fixed #d8e9ba, dim #bccca0.
- Secondary (taupe): #655e51, container #e9dece.
- Tertiary (bronze/caramel): #7d5325, container #996b3b, tint #ffdcbd.
- Error: #ba1a1a, container #ffdad6, on-error-container #93000a.
- Dark mode (warm charcoal, not cold slate): background #181a15, card #20221c, surface tiers #23261f / #2a2d25 /
  #32362d / #3b3f35, text #f2f1ec, secondary text #cfc5b5, borders #3b3e35. Design every screen in light mode and
  provide the dark variant of the main screens.
- Shape: cards radius 16, inputs/buttons radius 12, chips fully rounded. Soft shadows only (very low opacity).
- Layout: the module lives inside the existing app shell (left sidebar with the KedaiMbo logo, outlet switcher on
  top, a "Produk & Stok" item highlighted, user avatar at the bottom; top bar with breadcrumb and a search field).
  Content max width 1280, 24 px page padding on desktop, 16 px on mobile.

STATUS COLOR MAPPING (stock):
- OK  -> "Aman"    olive tint chip (#d8e9ba bg, #3e4b29 text)
- LOW -> "Menipis" bronze chip (#ffdcbd bg, #643e11 text)
- OUT -> "Habis"   taupe chip (#e9dece bg, #4c463a text)
- NEGATIVE -> "Minus" red chip (#ffdad6 bg, #93000a text) with a small warning icon
Never rely on color alone: every chip has an icon and a text label.

BEHAVIOR PRINCIPLES:
- Categories are DYNAMIC data created by the owner (e.g. "Kopi", "Non-Kopi", "Makanan", "Minuman", "Snack").
  Do NOT hard-code a Food/Drink toggle. Show categories as filter chips and as an editable list.
- Variants (e.g. "Ukuran", "Suhu": required, choose exactly 1) and add-ons (e.g. "Tambahan": optional, choose 0..2)
  are the SAME concept: an "Grup Pilihan" with a "Wajib dipilih" switch and min/max selections, containing options
  with a price adjustment (+Rp5.000) and an availability switch.
- STOCK MAY GO NEGATIVE. The system never blocks a sale because of stock; it only warns. The design must make
  warnings visible and friendly, never scolding: "Stok Susu minus (-20 ml). Periksa hitungan stok."
- Price changes require a short reason (an inline field that appears when the price is edited).
- Role rules: OWNER can do everything. CASHIER sees products read-only and can only record "Stok Masuk"
  (restock) and "Bahan Terbuang" (waste); the "Koreksi Stok" (adjustment), ingredient creation/editing and
  recipe editing are hidden or disabled with a lock tooltip "Hanya pemilik".
- Every list has loading skeletons, an empty state with a friendly illustration-free message and a primary
  action, and an error state with "Coba lagi".
- Accessibility: contrast AA, visible focus rings (2 px olive), minimum touch target 44 px, labels on all inputs,
  keyboard reachable drawers/dialogs.
```

---

## BAGIAN B — PROMPT PER LAYAR (tempel satu per satu)

### B1 — Halaman utama "Produk & Stok" (Tab Produk)

```
Design the main page "Produk & Stok" with three tabs under the page title: "Produk", "Kategori", "Stok Bahan".
Show the "Produk" tab selected (desktop 1440).

Header: title "Produk & Stok", subtitle "Kelola menu, varian, resep, dan stok bahan outlet Anda." and a primary
button "Tambah Produk" (owner only) plus a secondary outline button "Atur Kategori".

Toolbar: search field "Cari produk…", horizontally scrollable category chips ("Semua", "Kopi", "Non-Kopi",
"Makanan", "Snack") with item counts, a select "Status" (Semua / Tersedia / Tidak tersedia), and a view toggle
(tabel / kartu).

Table view columns: Foto (48 px rounded thumbnail or initial letter fallback), Nama + deskripsi satu baris,
Kategori (chip), Harga ("Rp25.000"), "Grup Pilihan" count (e.g. "2 grup"), "Resep" status
("Sudah diatur" olive check chip / "Belum ada resep" muted chip), "Tersedia" switch, and a "…" menu
(Edit, Atur Varian & Tambahan, Atur Resep, Arsipkan). Rows have hover state. Show 8 realistic rows, one row with
the availability switch off, and one row that has a bronze "Stok Menipis" badge next to the name (a main
ingredient of its recipe is low).

Card view: 4-column grid of product cards with image, name, price, category chip, availability switch.

Footer: pagination "Menampilkan 1–8 dari 24 produk".
Also show a slim info banner above the table: "2 bahan menipis dan 1 bahan minus. Lihat Stok Bahan" with a link
to the Stok Bahan tab (bronze tint).
```

### B2 — Form Tambah/Edit Produk (drawer kanan)

```
Design a right-side drawer (width 560) "Tambah Produk" (and its edit state "Edit Produk") opened from the product
list. Sections stacked with section titles:
1. "Informasi Produk": image URL field with live preview box ("Tempel tautan gambar"), "Nama produk" (required,
   max 160), "Kategori" (select of dynamic categories + inline "Buat kategori baru"), "Deskripsi" (textarea, max 1000,
   character counter), "Harga" (prefix "Rp", numeric, 2 decimals allowed), "Urutan tampil" (number),
   switch "Tersedia untuk dijual".
2. When editing and the price value differs from the saved price, an inline bronze-tinted panel appears under the
   price: "Harga berubah dari Rp25.000 menjadi Rp28.000. Alasan perubahan harga wajib diisi." with a required
   "Alasan perubahan harga" text input (max 255).
3. Footer sticky: "Batal" (outline) and "Simpan Produk" (primary); in edit mode also a destructive text button
   "Arsipkan produk" with a confirm dialog "Produk diarsipkan tidak muncul di menu, tetapi riwayat pesanan tetap aman."
Show validation errors under fields in red (e.g. "Nama produk wajib diisi.", "Harga tidak boleh negatif.").
Show both the empty "Tambah" state and the filled "Edit" state with the price-reason panel visible.
```

### B3 — Detail Produk: Varian & Tambahan (Grup Pilihan)

```
Design a full-height drawer or page "Varian & Tambahan — Kopi Susu Gula Aren" with a header showing the product
thumbnail, name, price and a back button. Content: a list of option-group cards ("Grup Pilihan"), each card
containing:
- Group header: drag handle, group name (inline editable), switch "Wajib dipilih", two small steppers
  "Minimal" and "Maksimal" (disabled/forced to match when "Wajib" is on: min ≥ 1), and a "…" menu.
- A helper line that explains the rule in plain Indonesian, auto-generated, e.g. "Pelanggan wajib memilih 1." or
  "Pelanggan boleh memilih 0–2."
- Option rows: name, "Selisih harga" with +/− and Rp (e.g. +Rp6.000, Rp0 for Regular), "Tersedia" switch,
  an inline chip "Resep: 18 g Biji Kopi" (or "Belum ada resep" muted) that opens the recipe editor for that option,
  and a delete/disable icon.
- "Tambah pilihan" row button at the bottom of each group.
Below all groups: a dashed-outline button "Tambah Grup Pilihan".
Use these sample groups: "Ukuran" (wajib, 1–1: Regular Rp0, Large +Rp5.000), "Tambahan" (opsional, 0–2:
Extra Shot +Rp6.000, Oat Milk +Rp8.000, Boba +Rp5.000).
When the price of an existing option is edited, show the same inline "Alasan perubahan harga" field as in B2.
Note on screen (info chip): "Pilihan dengan resep akan mengurangi stok bahan saat pesanan dibayar."
```

### B4 — Editor Resep (produk dan pilihan)

```
Design a modal/drawer "Atur Resep — Kopi Susu Gula Aren" (and the same component reused for an option such as
"Resep Pilihan — Boba"). Explain in one line: "Tentukan bahan yang terpakai untuk 1 porsi. Stok berkurang
otomatis saat pesanan dibayar."
Body: a list of ingredient rows, each with an ingredient select (searchable, shows name + current stock + status
chip), a numeric "Jumlah per porsi" input, and a read-only unit label that follows the ingredient (g, ml, pcs).
Row actions: remove. Below: "Tambah bahan" button. Prevent duplicate ingredients (show inline error
"Bahan sudah ada di resep ini.").
Right/bottom summary card "Ringkasan 1 porsi": e.g. "Biji Kopi 18 g · Susu 120 ml · Gula Aren 20 ml".
Empty state: "Belum ada resep. Produk ini tidak akan mengurangi stok." Footer: "Batal", "Simpan Resep"
(owner only; for a cashier show the recipe read-only with a lock note).
Show a filled state with 3 ingredients and an empty state.
```

### B5 — Tab Kategori

```
Design the "Kategori" tab: a clean list of dynamic categories with drag handles for ordering, name, product count
("12 produk"), and a "…" menu (Ubah nama, Arsipkan). Top-right primary button "Tambah Kategori" opens a small
inline popover or dialog with a single "Nama kategori" input (max 120) and "Urutan tampil". Show validation for a
duplicate name: "Nama kategori sudah dipakai di outlet ini." Show 5 sample categories: Kopi, Non-Kopi, Makanan,
Minuman Dingin, Snack. Include the confirm dialog for archive: "Produk di kategori ini akan disembunyikan dari menu."
Empty state message: "Belum ada kategori. Buat kategori pertama, misalnya Makanan atau Minuman."
```

### B6 — Tab Stok Bahan (daftar stok)

```
Design the "Stok Bahan" tab. Top: four summary cards in a row: "Total Bahan" (24), "Menipis" (3, bronze),
"Habis" (1, taupe), "Minus" (1, red). Cards act as quick filters (clicking filters the table below).
Toolbar: search "Cari bahan…", select "Status" (Semua/Aman/Menipis/Habis/Minus), primary button "Tambah Bahan"
(owner only).
Table columns: Bahan (name + SKU in small text), Satuan (g, ml, pcs), Stok Saat Ini (right-aligned, tabular
numbers, negative values in red with a minus sign), Batas Menipis, Status chip, Aksi. Row actions as compact
buttons: "Stok Masuk" (primary tonal), "Terbuang" (outline), and a "…" menu with "Koreksi Stok" (owner only, locked
for cashier), "Riwayat", "Ubah bahan", "Nonaktifkan".
Use sample rows: Biji Kopi 1.250 g (Aman), Susu 380 ml (Menipis, limit 500), Gula Aren 0 ml (Habis), Oat Milk -150 ml
(Minus), Boba 900 g (Aman), Bubuk Matcha 210 g (Aman), Croissant 14 pcs (Aman).
Under the table add a gentle note: "Stok boleh minus. Sistem hanya memberi peringatan agar penjualan tidak terhenti."
Show a cashier variant of the same screen where "Tambah Bahan" and "Koreksi Stok" are hidden/locked.
```

### B7 — Dialog "Catat Pergerakan Stok" + Riwayat

```
Design two things.
(1) A dialog "Catat Stok — Susu" with a segmented control of three types: "Stok Masuk" (restock, +),
"Terbuang/Rusak" (waste, −), "Koreksi" (adjustment ±, owner only; disabled with a lock for cashiers).
Fields: "Jumlah" with the ingredient's unit as suffix (e.g. ml), "Alasan" textarea (optional for Stok Masuk with
placeholder "Contoh: belanja pasar pagi", REQUIRED for Terbuang and Koreksi), and for Koreksi a toggle
"Tambah / Kurangi". A live preview card: "Stok sekarang 380 ml → setelah 1.380 ml" (turns red with
"Stok akan menjadi minus" if the result is below zero, but the save button stays enabled — warn, never block).
Buttons: "Batal", "Simpan". Include the inline error state "Kunci duplikat" is NOT user-visible; instead show a success
toast "Stok Susu diperbarui".
(2) A right drawer "Riwayat Stok — Susu": a vertical timeline of movements, newest first. Each entry: type icon
and label (Stok Masuk / Terbuang / Koreksi / Penjualan), signed quantity (+1.000 ml in olive, −120 ml in
bronze/red), reason, who did it ("oleh Kasir Sari"), date-time "10 Nov 2026 14:32", and for "Penjualan" entries a
link "Pesanan #R-1042". Include "Muat lebih banyak" at the bottom.
```

### B8 — Peringatan stok di kasir & tambah bahan baru

```
Design three small pieces:
(1) The toast/banner that appears on the cashier screen right after a successful payment when the sale pushed some
ingredients low or negative: title "Pembayaran berhasil", body list: "Susu: sisa −20 ml (minus)" and "Gula Aren: habis",
button "Lihat Stok Bahan". Tone: informative bronze tint, dismissible, never red-alarming.
(2) The drawer "Tambah Bahan": fields "Nama bahan" (required, max 140), "SKU" (optional), "Satuan" (select or free
text: g, ml, pcs, kg, liter; note "Satu satuan per bahan, tidak ada konversi"), "Batas menipis" (number), and
"Stok awal" (optional number, helper "Dicatat sebagai Stok Masuk 'Stok awal'"). Buttons "Batal" / "Simpan Bahan".
Show validation for duplicate name: "Nama bahan sudah ada di outlet ini."
Edit mode note: "Satuan tidak dapat diubah setelah ada riwayat stok."
(3) A compact "Stok Menipis" widget for the dashboard (list of up to 5 ingredients with status chips and a
"Lihat semua" link).
```

---

## BAGIAN C — PROMPT PERBAIKAN (jalankan setelah semua layar jadi)

```
Review all generated screens for consistency: same spacing scale (4/8/12/16/24), same chip, switch, table and
drawer components everywhere; same page header pattern; every screen uses only the KedaiMbo tokens from the master
context; all copy is Indonesian; currency is "Rp25.000" format and quantities use thousand separators with the
unit ("1.250 g"). Make sure every status chip has an icon plus text. Produce the dark-mode versions of B1, B3, B6
and B7 using the warm charcoal tokens. Produce tablet (834 px) and mobile (390 px) versions of B1, B6 and B7:
on mobile the table becomes stacked cards, drawers become full-screen bottom sheets, the three tabs become a
scrollable segmented control, and primary actions stay reachable with the thumb (sticky bottom bar).
```

---

## BAGIAN D — REFERENSI KONTRAK API (agar UI sesuai backend)

Semua respons dibungkus `ApiResponse<T>` (`success`, `message`, `data`). Pesan dari server berbahasa Indonesia. Uang/kuantitas berupa angka desimal (uang 2 digit, kuantitas bahan 3 digit). Semua path memakai prefix `/api/v1`. Rute `/merchant/**` hanya OWNERMERCHANT; rute `/cashier/**` boleh OWNERMERCHANT dan CASHIER.

### Katalog (sudah ada)

| Fungsi | Method + path | Peran |
|---|---|---|
| Daftar kategori | `GET /cashier/outlets/{outletId}/fnb/categories` | Owner, Kasir |
| Buat / ubah / arsip kategori | `POST` / `PUT …/{id}` / `DELETE …/{id}` di `/merchant/outlets/{outletId}/fnb/categories` | Owner |
| Daftar produk | `GET /cashier/outlets/{outletId}/fnb/products` | Owner, Kasir |
| Buat / ubah / arsip produk | `POST` / `PUT …/{id}` / `DELETE …/{id}` di `/merchant/outlets/{outletId}/fnb/products` | Owner |
| Grup pilihan | `GET /cashier/outlets/{o}/fnb/products/{pid}/groups`, `POST`/`PUT` di `/merchant/…/products/{pid}/groups[/{id}]` | Owner (tulis) |
| Pilihan (opsi) | `GET /cashier/…/groups/{gid}/options`, `POST`/`PUT` di `/merchant/…/groups/{gid}/options[/{id}]` | Owner (tulis) |

Field respons: **Kategori** `{id, name, displayOrder, archived}`. **Produk** `{id, categoryId, name, description, price, imageUrl, available, archived, displayOrder}`. **Grup** `{id, productId, name, required, minimumSelections, maximumSelections, displayOrder}`. **Opsi** `{id, optionGroupId, name, priceAdjustment, available, displayOrder}`.
Validasi: nama kategori ≤ 120 (unik per outlet); nama produk ≤ 160; deskripsi ≤ 1000; `imageUrl` ≤ 512; harga ≥ 0 (maks 2 desimal); nama grup/opsi ≤ 120; aturan grup: `required` ⇔ `minimumSelections > 0`, dan `min ≤ max`. **Mengubah harga produk atau selisih harga opsi wajib mengisi `priceChangeReason` (≤ 255)**. Tidak ada endpoint arsip untuk grup/opsi (pakai `available`).

### Inventori & resep (kontrak final TASK-061; sedang diimplementasikan)

| Fungsi | Method + path | Peran |
|---|---|---|
| Buat bahan | `POST /merchant/outlets/{o}/fnb/inventory/items` | Owner |
| Ubah / nonaktifkan bahan | `PUT` / `DELETE /merchant/outlets/{o}/fnb/inventory/items/{id}` | Owner |
| Daftar stok (+filter `?status=`) | `GET /cashier/outlets/{o}/fnb/inventory/items` | Owner, Kasir |
| Catat pergerakan stok | `POST /cashier/outlets/{o}/fnb/inventory/items/{id}/movements` | Owner, Kasir (tipe `ADJUSTMENT` hanya Owner) |
| Riwayat stok | `GET /cashier/outlets/{o}/fnb/inventory/items/{id}/movements?page=&size=` (size 1–50) | Owner, Kasir |
| Resep produk | `GET` / `PUT /merchant/outlets/{o}/fnb/products/{pid}/recipe` | Owner |
| Resep pilihan | `GET` / `PUT /merchant/outlets/{o}/fnb/products/{pid}/groups/{gid}/options/{id}/recipe` | Owner |

- **Bahan (request)**: `name` (wajib, ≤ 140), `sku` (≤ 80), `unit` (wajib, ≤ 30), `reorderLevel` (≥ 0, 3 desimal), `initialQuantity` (≥ 0, hanya saat buat). **Bahan (respons)**: `{id, sku, name, unit, reorderLevel, active, quantityOnHand, stockStatus}` dengan `stockStatus` ∈ `OK | LOW | OUT | NEGATIVE`.
- **Gerakan stok (request)**: `type` ∈ `RESTOCK | WASTE | ADJUSTMENT`, `quantity` (RESTOCK/WASTE > 0; ADJUSTMENT bertanda dan ≠ 0), `reason` (≤ 1000; wajib untuk WASTE dan ADJUSTMENT; RESTOCK default "Restock"), `idempotencyKey` (wajib, ≤ 128; UI membuat UUID baru per submit dan memakai ulang saat retry). **Respons**: `{id, inventoryItemId, type, quantityDelta, reason, orderId, occurredAt, actorMembershipId}`; tipe `SALE` muncul otomatis di riwayat saat pesanan dibayar.
- **Resep (request)** `{lines: [{inventoryItemId, quantityPerUnit}]}` (maks 50 baris, tanpa bahan ganda, `quantityPerUnit > 0`); PUT mengganti seluruh resep, daftar kosong menghapus resep. **Respons** `{lines: [{inventoryItemId, inventoryItemName, unit, quantityPerUnit}]}`.
- **Peringatan stok setelah bayar**: respons pembayaran memuat `stockWarnings: string[]` (contoh `"Susu: sisa -20 ml (minus)"`). Daftar kosong berarti tidak ada peringatan.
- Aturan status: `NEGATIVE` jika stok < 0, `OUT` jika = 0, `LOW` jika ≤ `reorderLevel`, selain itu `OK`.
- Stok boleh minus: UI **tidak boleh** memblokir penyimpanan atau penjualan karena stok; hanya memberi peringatan.

---

## BAGIAN E — CHECKLIST HASIL (centang sebelum dipakai developer)

- [ ] Semua teks Bahasa Indonesia, format `Rp25.000` dan `1.250 g` konsisten.
- [ ] Hanya memakai token KedaiMbo (olive `#53613c`, krem `#fbf9f4`, perunggu `#7d5325`, error `#ba1a1a`); dark mode hangat tersedia untuk B1, B3, B6, B7.
- [ ] Kategori dinamis (tidak ada toggle Makanan/Minuman yang dikunci).
- [ ] Varian dan tambahan memakai satu komponen "Grup Pilihan" (Wajib dipilih + min/maks).
- [ ] Alasan perubahan harga muncul saat harga produk atau selisih harga opsi diubah.
- [ ] Empat status stok (Aman/Menipis/Habis/Minus) punya ikon + teks, bukan warna saja.
- [ ] Stok minus tidak memblokir tombol simpan; hanya peringatan.
- [ ] Varian Kasir: tidak ada Tambah Bahan, Koreksi Stok, atau Atur Resep (terkunci dengan tooltip "Hanya pemilik").
- [ ] Setiap daftar punya skeleton loading, empty state, dan error state "Coba lagi".
- [ ] Varian tablet 834 px dan mobile 390 px untuk B1, B6, B7.
- [ ] Fokus keyboard terlihat, target sentuh ≥ 44 px, kontras AA.
