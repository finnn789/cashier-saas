# Project Memory — SaaS Cashier

## Prioritas aktif — 28 September 2026

Pengguna mengubah prioritas kembali ke **F&B sebagai produk pertama**. Laundry ditunda dan fiturnya dinonaktifkan untuk penggunaan operasional baru; data tenant dan transaksi Laundry lama tidak dihapus. PRD F&B di `PRD-MVP.md` kembali menjadi target MVP aktif. Freetier F&B tahap awal memakai fondasi autentikasi dan kasir sederhana yang sudah ada, sementara kategori/produk, meja/QR, antrean dapur, pembayaran order, dan laporan masih memerlukan implementasi sesuai milestone PRD. Ketentuan Freetier Laundry sebelumnya hanya berlaku sebagai riwayat.

## Riwayat arah — 16 September 2026

Pengguna menetapkan platform SaaS multi-bisnis dengan **Laundry sebagai produk pertama**. Fondasi mencakup akun owner, katalog paket platform Freetier/Premium/Pro yang berlaku untuk semua jenis bisnis, pembayaran langganan gateway, onboarding merchant, serta aplikasi merchant dengan branding sendiri. Operasional pertama mencakup layanan kiloan/satuan, pelanggan, proses cucian, tagihan saat siap, pembayaran pelanggan, antar-jemput, dan inventory dasar. Role merchant adalah Owner/Cashier; desain mempertahankan kode `OWNERMERCHANT`/`CASHIER`, dengan administrasi platform terpisah.

Keputusan Freetier 23 September 2026: paket ini gratis dan hanya untuk pencatatan kasir biasa. Kasir memasukkan transaksi serta nama pelanggan secara manual dan mencatat penerimaan uang pelanggan secara manual. Data transaksi disimpan di database SaaS dan dapat dibuka lagi setelah login dari perangkat lain yang diizinkan; tidak terbatas pada satu komputer. Freetier diaktifkan tanpa invoice atau gateway pembayaran langganan platform. Batas fitur lainnya belum dipastikan.

Implementasi Freetier sekarang menyimpan kode paket langsung pada `SubscriptionEntity` (`plan_code = FREETIER`), tanpa `PlanEntity`, `PlanVersionEntity`, atau `PlanPriceEntity`. Katalog publik tetap berupa kode paket aplikasi. Transaksi kasir sederhana menggunakan katalog `cashier_catalog_items` dan snapshot pada `cashier_sales`/`cashier_sale_items`; alur ini terpisah dari lifecycle pengerjaan `laundry_orders`. Owner mengatur katalog dan membuat akun kasir, kasir memilih layanan dari katalog, memasukkan nama pelanggan serta pembayaran, dan riwayat tersimpan di database SaaS. Skema runtime ada pada Flyway V5. Desain harga paket berbayar dan migrasi dari model referensi lama masih perlu keputusan tersendiri.

[Rancangan database Laundry v1](docs/database/README.md) dan SQL di `docs/database/` masih merupakan artefak desain untuk operasional Laundry lengkap, bukan skema runtime Freetier. Katalog publik menetapkan identitas Freetier/Premium/Pro di kode aplikasi; harga paket berbayar, pembagian fitur selain Freetier dasar, ketersediaan per jenis bisnis, gateway, serta kanal notifikasi belum ditentukan. Identitas runtime menggunakan `StaffUserEntity` sebagai akun global dan `TenantMembershipEntity` untuk tenant/role; Flyway V4 mengalihkan relasi membership ke `staff_users`, sementara V5 menambah onboarding dan kasir Freetier. Tabel `users` lama tetap ada untuk pemulihan, tetapi tidak dipakai autentikasi. Bagian F&B di bawah adalah riwayat keputusan, bukan scope produk pertama yang terbaru.

Dokumen ini adalah ringkasan pengetahuan dan keputusan proyek yang stabil. Baca bersama `PRD-MVP.md` sebelum merancang atau mengimplementasikan fitur.

## Identitas Proyek

- Nama kerja: SaaS Cashier / OrderFlow.
- Jenis produk: SaaS multi-tenant untuk kasir dan manajemen pesanan F&B.
- Target awal: kafe, restoran, atau usaha F&B kecil dengan 1–3 outlet.
- Tahap saat ini: definisi MVP.
- Bahasa produk utama: Bahasa Indonesia.
- Mata uang awal: IDR.
- Kanal aplikasi: web responsif untuk komputer, tablet, dan ponsel.

## Masalah Inti

Pesanan pelanggan melalui QR dan pesanan langsung melalui kasir sering berjalan pada alur yang terpisah. Produk harus menyatukan keduanya ke satu antrean yang dapat diproses oleh kasir dan dapur.

## Nilai Inti Produk

- Pelanggan memesan lewat QR tanpa instal aplikasi dan tanpa akun.
- Kasir dapat memasukkan pesanan langsung melalui POS.
- Pesanan QR dan kasir berada dalam satu sistem dan satu antrean.
- Dapur dapat melihat dan memperbarui progres pesanan.
- Owner dapat mengelola menu, meja, staf, transaksi, dan laporan dasar.

## Peran MVP

- Owner
- Manager
- Cashier
- Kitchen
- Customer tanpa akun

## Status Pesanan

`NEW → CONFIRMED → PREPARING → READY → COMPLETED`

Pesanan juga dapat menjadi `CANCELLED`. Pembatalan wajib memiliki alasan dan audit log.

## Status Pembayaran

- `UNPAID`
- `PAID`
- `REFUNDED` disiapkan pada model data, tetapi refund otomatis bukan bagian MVP.

## Keputusan Scope MVP

Termasuk:

- Multi-tenant dan isolasi data.
- Login staf dan role-based access.
- Pengaturan bisnis serta outlet dasar.
- Menu, kategori, produk, varian/topping sederhana, dan ketersediaan.
- Meja dan QR unik.
- Menu publik dan checkout tanpa login.
- POS kasir untuk dine-in dan takeaway.
- Antrean pesanan terpadu dan pembaruan real-time.
- Tampilan dapur.
- Pembayaran tunai dan non-tunai yang dicatat manual.
- Struk browser.
- Dashboard, transaksi, laporan dasar, dan ekspor CSV.
- Audit minimum untuk harga, diskon, pembatalan, dan pembayaran.

Tidak termasuk untuk MVP:

- Payment gateway otomatis.
- Inventori bahan baku dan resep.
- Loyalty/membership.
- Integrasi WhatsApp, marketplace, atau akuntansi.
- Reservasi, payroll, supplier, dan procurement.
- Split bill dan multi-payment.
- Operasi offline penuh.
- AI analytics.

## Aturan yang Tidak Boleh Dilanggar

- Total pesanan selalu dihitung ulang oleh server.
- Harga dan nama item disimpan sebagai snapshot pada transaksi.
- Pesanan yang sudah dibayar tidak boleh dihapus.
- Setiap data operasional harus terikat tenant; data antar-tenant tidak boleh bocor.
- Token QR harus acak dan tidak mengekspos ID internal berurutan.
- Checkout perlu idempotency key untuk mencegah pesanan ganda.
- Uang tidak boleh dihitung dengan floating point.
- Perubahan penting harus menyimpan aktor, waktu, dan alasan bila relevan.

## Alur Produk Wajib

`buat menu → buat meja/QR → pelanggan atau kasir membuat pesanan → dapur memproses → kasir menerima pembayaran → owner melihat laporan`

Fitur yang tidak diperlukan untuk menyelesaikan alur ini masuk backlog setelah MVP.

## Asumsi Saat Ini

- Pemesanan QR dilakukan di lokasi usaha, terutama untuk dine-in.
- Pelanggan menggunakan browser ponsel.
- Kasir atau manager mengonfirmasi pesanan QR sebelum dapur memprosesnya; keputusan ini masih dapat diubah setelah pilot.
- Pembayaran QR pada MVP belum terhubung langsung ke payment gateway.
- Printer struk menggunakan kemampuan cetak browser sampai integrasi hardware dipilih.

## Target Validasi

- Minimal 3 bisnis pilot.
- Minimal 100 transaksi end-to-end.
- Tingkat keberhasilan pembuatan pesanan minimal 99% selama pilot.
- Median waktu kasir membuat pesanan sederhana di bawah 60 detik setelah pelatihan singkat.

## Pertanyaan Terbuka

- Nama final produk.
- Dukungan satu atau beberapa outlet pada rilis pertama.
- Konfirmasi pesanan QR manual atau otomatis.
- Batas diskon berdasarkan peran.
- Printer prioritas.
- Paket harga dan trial.
- Stack teknologi dan hosting.

## Sumber Kebenaran

- Ruang lingkup dan acceptance criteria: `PRD-MVP.md`.
- Dokumen ini hanya ringkasan memory; jika terjadi perbedaan, ikuti PRD versi terbaru.
