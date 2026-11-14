# Product Requirements Document — Kasir Cafe + Asisten AI Owner

**Versi:** 2.1
**Status:** Aktif
**Tanggal:** 13 November 2026
**Menggantikan:** PRD v0.1 (13 September 2026) beserta catatan keputusan 16 September – 6 Oktober 2026 (ringkasannya ada di §17).
**Fokus:** aplikasi kasir (POS) berbasis web untuk cafe/coffee shop kecil dengan satu outlet, ditambah asisten AI yang membantu owner mengambil keputusan.

## 1. Ringkasan Produk

Kasir web untuk cafe kecil yang **hanya mencatat** transaksi (aplikasi tidak memproses uang dan tidak terhubung ke penyedia pembayaran): kasir mencatat pesanan dan pembayaran dengan cepat, owner mengontrol uang dan stok, dan asisten AI (Telegram, lalu chat web) merangkum data menjadi jawaban dan saran dalam bahasa sehari-hari.

Diferensiator: owner tidak perlu membaca laporan; asisten AI mengirim ringkasan, peringatan, dan saran (stok, target, anomali kasir) berdasarkan data transaksi yang lengkap dan bisa dipercaya.

## 2. Masalah yang Diselesaikan

- Uang kas bisa bocor tanpa jejak (pembatalan, diskon, selisih laci) karena tidak ada kontrol shift.
- Owner tidak tahu margin per menu karena harga pokok bahan tidak tercatat.
- Stok bahan habis tanpa peringatan, atau owner salah menghitung.
- Laporan POS biasa harus dibuka dan dibaca sendiri; owner kecil jarang sempat.
- Aplikasi POS lengkap terlalu rumit atau mahal untuk satu outlet.

## 3. Prinsip Produk

1. **Kasir harus bisa dipercaya dulu, baru AI.** Kontrol kas dan kelengkapan data didahulukan; AI hanya membaca data yang sudah benar.
2. **Satu niche:** cafe/coffee shop dengan satu outlet. Bukan retail, bukan multi-vertical.
3. **AI read-only lebih dulu.** Aksi tulis lewat AI hanya setelah fondasi stabil dan selalu dengan konfirmasi eksplisit serta audit.
4. **Pencatatan saja, tetap sederhana.** Pembayaran hanya dicatat (tunai atau non-tunai dengan label); tidak ada integrasi pembayaran. Fitur lanjutan (inventori, resep, option group) opsional dan tidak wajib diisi sebelum bisa berjualan.
5. **Fitur yang tidak mendukung alur §9 masuk backlog.**

## 4. Sasaran

- Kasir menyelesaikan transaksi sederhana di bawah 60 detik.
- Setiap rupiah yang masuk/keluar laci tercatat per shift, dan selisih kas terlihat saat tutup shift.
- Owner melihat omzet, margin, dan stok menipis tanpa membuka dashboard (ringkasan Telegram).
- Data antar tenant terisolasi penuh.

## 5. Di Luar Cakupan

- Pemesanan mandiri pelanggan lewat QR, halaman publik pelanggan, layar dapur (KDS), manajemen meja, real-time WebSocket (dihapus pada pivot 12 November 2026; polling 15 detik dipakai sebagai gantinya).
- Multi-outlet tingkat lanjut dan laporan konsolidasi (satu tenant = satu outlet untuk rilis ini).
- Loyalty, membership pelanggan, voucher kompleks.
- Integrasi marketplace/delivery, akuntansi lengkap, payroll, reservasi.
- Manajemen supplier dan purchase order (harga beli dicatat langsung saat restock, §8.6).
- Semua integrasi pembayaran: payment gateway, QRIS sebagai fitur pembayaran (termasuk menampilkan QRIS di aplikasi), EDC. QRIS/transfer/debit hanya dicatat sebagai label pembayaran non-tunai (§8.9). Gateway mock dihapus (M5).
- Split bill, cicilan, beberapa metode bayar dalam satu transaksi, refund parsial.
- Operasi offline penuh, integrasi WhatsApp.
- Aksi tulis oleh AI (ditunda ke setelah M7).

## 6. Pengguna dan Peran

Peran sistem tetap `SUPERADMIN`, `OWNERMERCHANT`, `CASHIER`.

- **Owner (`OWNERMERCHANT`):** mengatur bisnis, menu, resep, stok, staf; memberi diskon; void/refund; melihat laporan, audit, dan shift; menerima ringkasan AI.
- **Kasir (`CASHIER`):** membuka/menutup shift, membuat dan menahan pesanan, menerima pembayaran, mencatat restock/waste, membatalkan pesanan terbuka tanpa diskon.

## 7. Model Pesanan

- Status pesanan: `OPEN` (ditahan/belum dibayar), `COMPLETED` (lunas), `CANCELLED`.
- Status pembayaran: `UNPAID`, `PAID`, `REFUNDED`.
- Tagihan yang ditahan (hold bill) adalah pesanan `OPEN`/`UNPAID` di server; itemnya dapat diganti selama belum ada diskon.
- Void/refund penuh mengubah `PAID` menjadi `REFUNDED`; status pesanan tetap `COMPLETED` sebagai jejak, dan laporan tidak menghitungnya.

## 8. Ruang Lingkup Fungsional

Kolom **Status** mengikuti kode di branch aktif; detail bukti ada di `.ai/MEMORY.md` dan `.ai/TASKS.md`.

### 8.1 Autentikasi, Tenant, dan Onboarding — sudah ada
- Registrasi, login, pilih merchant/outlet, JWT Bearer + refresh cookie, onboarding Freetier (tenant, langganan, outlet pertama).
- Ganti password sendiri. *(M4, TASK-076)*

### 8.2 Manajemen Staf
- Undang kasir via kode sekali pakai (24 jam) — sudah ada.
- Owner melihat daftar kasir outlet, menonaktifkan/mengaktifkan kembali kasir (akses dan sesi langsung berhenti, bot Telegram ikut menolak), dan mereset password kasir menjadi password sementara sekali tampil. Reset ditolak bila akun kasir terhubung ke bisnis lain. *(M4, TASK-076)*

### 8.3 Pengaturan Bisnis — sudah ada, perlu dilengkapi
- Nama usaha, logo, alamat, telepon, mata uang (terkunci setelah ada pesanan), pajak, biaya layanan, zona waktu outlet.
- Tambahan: footer struk dan daftar label pembayaran non-tunai yang diatur owner (mis. "QRIS", "Transfer BCA", "Debit"). *(M5)*

### 8.4 Menu dan Option Group — sudah ada
- Kategori dinamis, produk, option group yang dapat dipakai ulang di level outlet (min/max/required), archive (bukan hapus), perubahan harga diaudit.

### 8.5 Inventori dan Resep — sudah ada, perlu dilengkapi
- Bahan baku (satu satuan per bahan), resep per produk dan per opsi, stok dipotong otomatis saat lunas, stok boleh minus dengan peringatan (NEGATIVE/OUT/LOW).
- Kasir boleh RESTOCK dan WASTE; ADJUSTMENT, bahan, dan resep hanya owner.
- Perbaikan: bahan yang dinonaktifkan tidak lagi dipotong lewat resep. *(M6)*

### 8.6 Harga Pokok dan Stok Opname *(M6)*
- RESTOCK mencatat harga beli per satuan (opsional); sistem menyimpan harga pokok rata-rata bergerak per bahan.
- Pergerakan SALE menyimpan snapshot harga pokok, sehingga HPP per transaksi dan per menu dapat dihitung.
- Stok opname: owner memasukkan jumlah fisik; sistem mencatat selisihnya sebagai ADJUSTMENT dengan alasan "Stok opname".

### 8.7 POS Kasir — sudah ada, perlu dilengkapi
- Buat pesanan dine-in/takeaway, pilih produk dan opsi, jumlah, catatan per item; total dihitung server; idempotency key wajib.
- Tahan pesanan (OPEN) dan ganti itemnya; batalkan dengan alasan.
- Nama pelanggan menjadi opsional dan ada catatan level pesanan. *(M5)*
- Mempercepat kasir (UI): tombol uang cepat ("Uang pas", 20rb, 50rb, 100rb), produk favorit/terlaris di atas, pencarian lewat keyboard, struk opsional ("Cetak struk"). *(M5, UI)*
- Daftar pesanan dapat difilter tanggal dan nomor pesanan, serta menampilkan umur tagihan yang ditahan. *(M5)*

### 8.8 Shift dan Kas *(M4)*
- Setiap pengguna yang menerima pembayaran harus memiliki shift terbuka di outlet tersebut; satu shift terbuka per pengguna per outlet.
- Buka shift: modal awal (≥ 0); nilainya diisi otomatis dari uang terhitung pada penutupan shift terakhir di outlet itu dan dapat diubah. *(prefill: M5)*
- Pengeluaran kas kecil: selama shift terbuka, kasir mencatat uang yang diambil dari laci (nominal > 0 dan keterangan wajib, mis. "Beli es batu"); pengeluaran diaudit dan dapat dilihat owner. *(M5)*
- Tutup shift: kasir memasukkan uang fisik di laci; sistem menghitung kas seharusnya = modal + tunai diterima (dibayar − kembalian) − refund tunai − pengeluaran kas kecil pada shift itu, lalu menyimpan selisih. *(pengeluaran: M5)*
- Ringkasan tutup shift berfungsi sebagai laporan harian: jumlah transaksi, omzet, tunai, non-tunai per label, pengeluaran, refund, pembatalan, diskon, dan selisih kas.
- Tagihan OPEN boleh tetap ada saat tutup shift; jumlahnya ditampilkan sebagai peringatan.
- Owner melihat daftar dan detail shift (siapa, kapan, selisih).

### 8.9 Pembayaran — sudah ada, perlu dilengkapi
- Hanya dua metode yang dicatat: **Tunai** (kembalian dihitung) dan **Non-tunai** (nominal harus sama dengan total).
- Non-tunai wajib memilih satu label dari daftar owner (§8.3) agar owner dapat mencocokkannya dengan mutasi rekening; label disimpan sebagai snapshot teks pada pembayaran. *(M5)*
- Gateway mock dan alur attempt/resolve dihapus dari kode; data lama tetap di database. *(M5; sebelumnya dimatikan lewat flag di TASK-076)*
- Satu pembayaran per pesanan; pembayaran tercatat dengan metode, nominal, kembalian, waktu, penerima, dan shift. *(shift: M4)*

### 8.10 Pembatalan, Void, dan Refund
- Pembatalan pesanan OPEN: owner, atau kasir bila pesanan tidak memiliki diskon; alasan wajib; diaudit. *(aturan kasir: M4, TASK-076)*
- Void/refund penuh pesanan lunas: hanya owner, alasan wajib, mengembalikan stok (pergerakan SALE_REVERSAL), refund tunai dicatat pada shift terbuka owner, diaudit. *(M4)*

### 8.11 Struk *(M5)*
- Nomor struk berurutan per outlet per hari menurut zona waktu outlet, format `INV-YYYYMMDD-NNNN`.
- Struk memuat nama usaha, logo, alamat/telepon outlet, item dan opsi, subtotal, diskon, pajak, biaya layanan, total, dibayar, kembalian, kasir, waktu lokal, dan footer.
- Struk dapat dicetak ulang dari browser; tanda "REFUND" bila sudah di-refund. Mencetak struk tidak wajib.

### 8.12 Laporan — sudah ada, perlu dilengkapi
- Sudah ada: ringkasan omzet (gross, diskon, net, pajak, layanan, tunai bersih), produk terlaris, rekap per metode bayar, daftar transaksi, ekspor CSV. Hanya transaksi `PAID` yang tidak dibatalkan yang dihitung.
- Tambahan *(M5)*: rekap non-tunai per label.
- Tambahan *(M6)*: penjualan per jam, per kasir, per shift; HPP dan laba kotor per menu dan total; jumlah refund.

### 8.13 Audit
- Tercatat: perubahan harga, resep, pembuatan pesanan *(TASK-076)*, ganti item, diskon, pembatalan, pembayaran, pengaturan, aksi staf (nonaktif/aktif/reset password) *(TASK-076)*, buka/tutup shift dan void/refund *(M4)*, pengeluaran kas kecil *(M5)*.
- Owner melihat audit log dengan filter aksi, pelaku, dan tanggal — sudah ada.

### 8.14 Asisten AI Owner *(M7)*
- Sudah ada: bot Telegram dengan identitas dari kontak terverifikasi; alat baca katalog dan status pesanan lewat gRPC, dibatasi pada tenant dan outlet milik member *(pembatasan outlet: TASK-076)*.
- Ringkasan harian otomatis ke owner setelah shift terakhir ditutup atau pada jam yang diatur: omzet, transaksi, tunai vs non-tunai per label, pengeluaran kas kecil, produk terlaris, stok menipis/minus, selisih kas, pembatalan/diskon/refund.
- Saran restock dari rata-rata pemakaian ditambah reorder level.
- Peringatan anomali: kasir dengan pembatalan, diskon, atau selisih kas di atas kebiasaan.
- Tanya-jawab data penjualan ("omzet minggu ini vs minggu lalu", "menu paling untung").
- AI hanya membaca. Data diambil dari backend Java (sumber kebenaran), tidak dari salinan.

## 9. Alur Utama

1. Owner mendaftar → onboarding → mengatur menu, resep, bahan, dan staf.
2. Kasir login → buka shift (modal awal terisi otomatis).
3. Kasir membuat pesanan (opsional ditahan dan diubah) → owner dapat memberi diskon.
4. Kasir mencatat pembayaran (tunai, atau non-tunai dengan label) → pesanan `COMPLETED`, stok terpotong, struk dapat dicetak.
5. Selama shift: kasir mencatat pengeluaran kas kecil bila mengambil uang dari laci.
6. Bila perlu: kasir membatalkan pesanan terbuka; owner melakukan void/refund pesanan lunas.
7. Kasir tutup shift → hitung laci → selisih tercatat → ringkasan harian.
8. Owner menerima ringkasan AI dan melihat laporan.

## 10. Aturan Bisnis

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

## 11. Entitas Data Inti

Tenant, Outlet, StaffUser, TenantMembership, MembershipOutletAccess, Subscription, BusinessSettings, Category, Product, OptionGroup, Option, RecipeLine, InventoryItem, InventoryBalance, InventoryMovement, SalesOrder, OrderItem, OrderItemOption, Discount, Payment, Shift *(M4)*, Refund *(M4)*, CashExpense *(M5)*, NonCashLabel *(M5)*, ReceiptSequence *(M5)*, AuditLog. Tabel gateway attempt tetap ada di database tetapi tidak dipakai setelah M5. Entitas QR/meja/dapur tetap ada di database (migrasi V1..V5 tidak diubah) tetapi tidak dipakai.

## 12. Kebutuhan Nonfungsional

- **Keamanan:** BCrypt, HTTPS di produksi, otorisasi di server pada setiap aksi, rate limit REST dan gRPC, kunci layanan internal untuk gRPC dan sinkronisasi member, tidak ada token/rahasia/nomor telepon di log.
- **Keandalan:** checkout, pembayaran, dan pergerakan stok idempotent; konflik konkurensi dijawab 409, bukan 500.
- **Kinerja:** halaman POS tampil < 3 detik; daftar pesanan diperbarui lewat polling 15 detik dan saat jendela kembali fokus.
- **UX:** POS untuk desktop dan tablet; status tidak hanya dibedakan warna; pesan error berbahasa Indonesia.

## 13. Acceptance Criteria Tingkat Produk

- **AC-01 Isolasi tenant:** pengguna tenant A tidak dapat membaca/mengubah data tenant B dan tidak mendapat bukti keberadaannya (REST dan gRPC/AI).
- **AC-02 Harga konsisten:** total tersimpan dihitung ulang di server dari harga aktif, opsi, pajak, layanan, dan diskon sah.
- **AC-03 Pembayaran:** pembayaran valid mengubah pesanan menjadi `COMPLETED`/`PAID`, memotong stok sekali, tercatat pada shift penerima, dan masuk laporan.
- **AC-04 Pembatalan:** hanya pengguna berwenang (§8.10), alasan wajib, audit tercatat.
- **AC-05 Ketersediaan produk:** produk habis/diarsipkan tidak dapat dipesan, termasuk bila berubah sesaat sebelum checkout.
- **AC-06 Kas shift:** saat tutup shift, kas seharusnya = modal + tunai bersih − refund tunai − pengeluaran kas kecil, dan selisih tersimpan.
- **AC-07 Void/refund:** pesanan lunas yang di-refund keluar dari laporan penjualan, stoknya kembali, dan audit tercatat.
- **AC-08 Akses staf:** kasir yang dinonaktifkan langsung ditolak di REST, refresh token, dan bot/gRPC.
- **AC-09 AI read-only:** asisten AI tidak dapat mengubah data dan hanya melihat outlet milik member yang bertanya.
- **AC-10 Non-tunai berlabel:** pembayaran non-tunai tanpa label aktif ditolak; rekap non-tunai per label sama dengan jumlah pembayaran non-tunai pada periode itu.

## 14. Metrik Produk

- Waktu median transaksi kasir; jumlah transaksi per hari per tenant.
- Rata-rata selisih kas per shift; rasio pembatalan, diskon, dan refund.
- Persentase owner yang membuka ringkasan AI harian.
- Tunai vs non-tunai per label; rata-rata pengeluaran kas kecil per shift.

## 15. Tahapan Implementasi

| Milestone | Isi | Status (13 Nov 2026) |
|---|---|---|
| M1 Fondasi | Auth, tenant, onboarding, undangan kasir, keamanan, rate limit | Selesai |
| M2 Katalog dan inventori | Menu, option group reusable, bahan, resep, stok | Selesai |
| M3 POS dan pembayaran | Pesanan, hold bill, diskon, bayar, struk dasar, laporan dasar, audit viewer, pivot status | Selesai (branch `refactor/pivot-cashier-ai`, belum di-merge ke main) |
| M4 Kontrol kasir | TASK-076 (staf, password, flag mock, aturan cancel, audit checkout, gRPC scoping); shift dan kas; void/refund (TASK-077) | Terimplementasi dan lolos `mvnw verify`, belum di-commit |
| M5 Pencatatan sederhana dan struk | Hapus mock gateway; label non-tunai dari owner; pengeluaran kas kecil di shift; modal awal otomatis; nomor struk berurutan + struk lengkap (nama usaha, logo, footer, tanda REFUND); nama pelanggan opsional + catatan pesanan; filter daftar pesanan; UI kasir cepat | Backend terimplementasi dan lolos `mvnw verify` (TASK-078), belum di-commit; UI via `prompts/m5-ui-cashier.md` |
| M6 Data owner | Harga pokok, stok opname, bahan nonaktif tidak dipotong, laporan per jam/kasir/shift, HPP/laba kotor | Belum |
| M7 Asisten AI | Ringkasan harian, saran restock, anomali, tanya-jawab penjualan (read-only) | Belum |
| M8 Pilot | 3 cafe pilot, 100+ transaksi nyata, umpan balik | Belum |

## 16. Keputusan yang Masih Terbuka

- Model harga langganan dan batas Freetier vs berbayar (enforcement paket belum ada).
- Jam kirim ringkasan AI harian (default: setelah shift terakhir ditutup, cadangan pukul 23.00 waktu outlet).
- Printer thermal yang diprioritaskan untuk pilot (sementara cetak dari browser).
- Batas diskon kasir (saat ini kasir tidak boleh memberi diskon).
- Kapan multi-outlet dibuka (perlu mengganti lock per tenant menjadi per outlet).

## 17. Riwayat Keputusan (ringkas)

- 13 Sep 2026: PRD v0.1 F&B dengan QR meja, dapur, dan kasir.
- 16–28 Sep 2026: arah Laundry ditetapkan lalu ditunda; F&B kembali menjadi produk pertama; Laundry dinonaktifkan tanpa menghapus data.
- 23 Sep 2026: Freetier = pencatatan kasir gratis.
- 30 Sep – 6 Okt 2026: Pay First, owner saja memberi diskon, gateway mock untuk tahap awal.
- 10 Nov 2026: inventori bahan dan resep dimasukkan ke cakupan; stok boleh minus.
- 12 Nov 2026: pivot menjadi kasir cafe + asisten AI owner; QR, dapur, meja, WebSocket dihapus; status OPEN/COMPLETED/CANCELLED; hold bill di server.
- 13 Nov 2026: PRD v2 (dokumen ini); keputusan kontrol kasir grup A (`.ai/DECISIONS.md` "Flow controls A").
- 13 Nov 2026 (v2.1): aplikasi hanya mencatat; QRIS bukan fitur pembayaran; pembayaran = tunai atau non-tunai berlabel; mock gateway dihapus; pengeluaran kas kecil dan modal awal otomatis masuk M5.
