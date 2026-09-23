# Rancangan database Laundry v1

Tanggal: 16 September 2026. Status: **rancangan referensi; baru fondasi identitas yang sebagian diterapkan, operasional Laundry belum**.

Arah produk dari percakapan terbaru adalah platform SaaS untuk beberapa jenis bisnis, dengan **laundry sebagai produk pertama**. Paket Freetier/Premium/Pro adalah katalog platform bersama yang tidak khusus Laundry atau F&B. PRD F&B yang lama menjadi referensi untuk pengembangan bisnis berikutnya. Rancangan ini mencakup fondasi berlangganan, merchant, operasional laundry, tagihan pelanggan, antar-jemput, dan persediaan dasar.

## Artefak

- [ERD dan alur utama](laundry-v1-erd.md): hubungan tabel dalam beberapa diagram kecil.
- [Kamus tabel dan aturan](laundry-v1-tables.md): tanggung jawab tabel, constraint, dan batas database/service.
- [SQL referensi PostgreSQL](laundry-v1.sql): struktur target untuk database kosong terpisah; bukan migration Flyway aplikasi saat ini.
- [Pemetaan entity lama](legacy-mapping.md): pemakaian kembali, pemisahan, dan tahapan perpindahan data.

SQL referensi Laundry di dokumen ini belum dijalankan sebagai migration aplikasi. Fondasi identitas memakai `StaffUserEntity` dan `tenant_memberships` melalui Flyway V4; katalog paket tersedia sebagai API kode tetap. Flyway V5 menambah onboarding dan kasir dasar Freetier. Workflow pengerjaan Laundry lengkap, harga paket berbayar, dan pembayaran langganan gateway belum tersedia.

Implementasi kasir Freetier memakai Flyway V5 dan model yang lebih kecil daripada SQL referensi ini. Sesuai keputusan terbaru, hanya `SubscriptionEntity` menyimpan `plan_code = FREETIER`; entity Plan/PlanVersion/PlanPrice tidak digunakan. Kasir Freetier memakai `cashier_catalog_items`, `cashier_sales`, dan `cashier_sale_items` untuk penjualan manual yang langsung dibayar. SQL referensi di bawah masih menggambarkan rancangan Laundry lengkap dan **bukan kontrak skema runtime Freetier**; model paket berbayar perlu dirancang ulang sebelum diimplementasikan.

## Sasaran dan batas rilis pertama

1. Owner membuat akun, memilih Freetier/Premium/Pro serta jenis bisnis Laundry, menyelesaikan aktivasi paket (termasuk pembayaran jika paket mensyaratkannya), lalu melengkapi profil merchant.
2. Owner mengatur outlet, kasir, layanan kiloan/satuan, tarif, dan pelanggan.
3. Owner/kasir menerima cucian, mencatat barang dan berat, memproses pesanan, lalu menandainya siap.
4. Sistem menerbitkan tagihan saat cucian siap; penyerahan barang dan pembayaran memiliki pencatatan terpisah.
5. Pengambilan dari pelanggan dan pengantaran kembali dapat dijadwalkan, termasuk permintaan jemput sebelum cucian ditimbang.
6. Persediaan operasional seperti deterjen dicatat sebagai mutasi masuk, pemakaian, dan penyesuaian.
7. Owner melihat tagihan, penerimaan pembayaran, antrean, dan audit yang konsisten untuk tenant/outlet miliknya.

Tidak ada tabel operasional F&B, retail, jasa booking, marketplace, atau distributor pada rilis ini. Belum ada mesin workflow generik, resep, procurement, payroll, komisi kurir, cicilan, deposit pelanggan, refund otomatis, atau integrasi kanal eksternal. Pembayaran gateway dalam desain ini adalah **pembayaran langganan ke platform**; pembayaran pelanggan laundry pertama dicatat manual, tunai atau non-tunai.

### Aturan Freetier

Freetier adalah paket gratis untuk kasir biasa: staf memasukkan transaksi dan nama pelanggan secara manual, lalu mencatat penerimaan uang pelanggan secara manual. Data transaksi disimpan di database SaaS dan dapat dibuka kembali setelah login dari perangkat lain yang diizinkan; pencatatan manual tidak berarti penyimpanan lokal di satu komputer. Memilih Freetier membuat subscription aktif tanpa menerbitkan `platform_invoices`, `platform_payment_attempts`, atau `gateway_events`. Profil merchant dan outlet tetap harus lengkap sebelum tenant menjadi `ACTIVE` dan token operasional diterbitkan. Pembayaran pelanggan tetap masuk domain transaksi merchant, terpisah dari pembayaran langganan platform.

Batas fitur Freetier selain pencatatan kasir manual belum diputuskan. Desain ini belum mengasumsikan QR pelanggan, antar-jemput, inventori, atau fitur paket lain sebagai hak Freetier.

## Keputusan rancangan

### Tenant adalah merchant

Gunakan `tenants` sebagai batas kepemilikan data merchant. Jangan membuat tabel `merchants` kedua untuk bisnis yang sama. Satu tenant menjalankan satu jenis bisnis; jenis yang diluncurkan pertama hanya Laundry. Outlet merupakan lokasi operasional tenant tersebut.

`staff_users` menyimpan identitas global pada runtime saat ini. `tenant_memberships` menyimpan hubungan akun dengan tenant serta role; satu akun dapat memiliki lebih dari satu merchant. Tabel `users` lama masih ada sebagai artefak transisi dan tidak dipakai autentikasi. Role merchant menggunakan kode `OWNERMERCHANT` (label Owner) dan `CASHIER`, agar istilah produk baru tetap mudah dipetakan ke kode yang ada. Administrator platform bukan role ketiga pada merchant.

### Website merchant adalah konfigurasi aplikasi bersama

Tenant memiliki slug, nama, logo, dan pengaturan. Contoh alamat: `/m/laundry-bersih`. Tidak perlu membuat database atau source code baru untuk setiap owner. Resolusi slug hanya menentukan merchant yang dituju; membership dan akses tetap diverifikasi server. Jangan memakai slug, UUID order, atau nomor nota sebagai bukti bahwa peminta boleh membaca data.

### Langganan dan pembayaran pelanggan dipisahkan

`platform_invoices` dan `platform_payment_attempts` mencatat owner membayar platform. `customer_invoices` dan `customer_payments` mencatat pelanggan membayar jasa laundry. Keduanya tidak digabung ke satu tabel payment dengan `entity_type/entity_id` yang kehilangan foreign key.

Harga serta fitur paket dipublikasikan sebagai versi. Perubahan harga/fitur menghasilkan versi penawaran baru, bukan mengubah histori subscription secara diam-diam. Freetier memiliki harga nol dan aktivasi tanpa pembayaran platform; harga paket berbayar dan matriks fitur lengkap belum ditentukan.

Hak akses efektif merupakan pertemuan jenis bisnis yang tersedia, entitlement langganan, membership/role, dan akses outlet. Paket tidak otomatis menyediakan semua jenis bisnis: ketersediaan jenis bisnis dan entitlement paket akan ditentukan kemudian. Cek tersebut dilakukan pada API dan service, bukan hanya melalui menu frontend.

### Identitas order umum, rincian laundry khusus

`orders` menjadi identitas transaksi bersama. `laundry_orders` serta `laundry_order_items` menampung informasi yang benar-benar khusus laundry. Tidak ada kolom meja, tipe dine-in, topping, atau status dapur pada transaksi laundry.

Relasi satu-ke-satu header/detail wajib dibuat dalam satu transaksi aplikasi. Foreign key dari detail ke header saja tidak menjamin setiap header sudah memiliki detail. Ketika bisnis kedua ditambahkan, discriminator dan validasi subtype perlu diperluas secara eksplisit; tidak cukup menambahkan pilihan bisnis di frontend.

SQL pertama membatasi lifecycle order pada kebutuhan laundry (`RECEIVED`, `WEIGHED`, `PROCESSING`, `READY`, `HANDED_OVER`, `COMPLETED`, `CANCELLED`). Fondasi ini dapat digunakan ulang, tetapi peluncuran bisnis kedua tetap membutuhkan migration/validasi baru untuk status dan subtype yang relevan. Desain ini tidak mengklaim enam bisnis sudah siap hanya lewat perubahan konfigurasi.

### Snapshot adalah bagian dari integritas histori

Nama layanan, satuan, harga satuan, jumlah tertagih, dan aturan tarif yang digunakan disimpan pada item transaksi. Invoice menyimpan rincian final tersendiri. Kontak/alamat pengiriman menggunakan snapshot saat penugasan. Perubahan katalog, pelanggan, atau alamat tidak boleh mengubah nota atau penugasan lama.

Header order dan invoice bukan dua sumber harga final yang bersaing: item order merupakan basis kalkulasi selama pengerjaan; invoice yang sudah diterbitkan adalah dokumen tagihan final. Penyesuaian setelah terbit memerlukan prosedur pembatalan/revisi dan audit, bukan mengedit histori pembayaran.

### Tiga keadaan berbeda: pengerjaan, penyerahan, pembayaran

`READY` berarti seluruh pekerjaan dalam pesanan siap. Saat itu invoice diterbitkan secara idempotent beserta event tagihan siap. `HANDED_OVER` berarti cucian sudah diserahkan. Pembayaran penuh yang valid melunasi invoice. Ketiga peristiwa tidak disatukan ke satu status.

Sesuai alur yang diminta, penyerahan dapat dicatat sebelum pembayaran; sistem tetap menampilkan tagihan belum lunas. Penyelesaian keseluruhan membutuhkan penyerahan selesai dan tagihan lunas. Jangan mengartikan status cucian selesai sebagai uang sudah diterima.

Untuk awal, progres produksi dikelola per order. Semua item harus selesai sebelum READY. Pemrosesan terpisah per kantong/pakaian, batch mesin, dan penyerahan sebagian belum termasuk rilis ini. Layanan setrika saja tidak boleh dipaksa melalui pencucian; aturan jalur layanan harus divalidasi saat fitur proses diterapkan.

## Alur dan transaksi yang harus atomik

### Pembelian paket dan onboarding

1. Autentikasi user global, lalu simpan tenant provisional yang profilnya belum lengkap, membership owner, pilihan paket, serta subscription pending.
2. Untuk Freetier, aktifkan subscription tanpa invoice atau payment attempt platform. Untuk paket berbayar, terbitkan invoice platform dan payment attempt dengan idempotency key. Tutup browser atau retry tidak boleh membuat subscription aktif kedua.
3. Khusus paket berbayar, server memverifikasi webhook gateway: signature, akun provider, payment reference, nominal, mata uang, dan status pembayaran. Redirect browser bukan bukti pembayaran.
4. Khusus paket berbayar, catat event provider dengan key unik; kunci invoice/subscription, rekonsiliasi pembayaran, dan aktifkan periode berlangganan satu kali. Event duplikat atau datang terlambat tidak mengulang provisioning.
5. Owner melengkapi profil dan outlet. Aktifkan penggunaan aplikasi setelah onboarding valid dan hak langganan berlaku. Pembayaran sukses tetapi profil belum lengkap harus dapat dilanjutkan.

Token akses operasional baru dapat dibuat setelah server memverifikasi membership tenant. Login sebelum tenant siap hanya memberi akses onboarding/akun terkait.

### Penerimaan, pengerjaan, penagihan

1. Temukan/buat pelanggan dalam tenant, pilih layanan aktif pada outlet, timbang barang, dan validasi satuan.
2. Hitung tarif di server, simpan order, detail, item snapshot, serta audit dalam satu transaksi. Key idempotency yang sama dengan payload berbeda ditolak.
3. Perubahan layanan, berat, tarif khusus, atau diskon harus mencatat aktor dan alasan yang relevan. Jangan mempercayai total kiriman browser.
4. Saat semua pekerjaan siap, kunci order; validasi belum dibatalkan dan item sudah final; terbitkan invoice beserta baris snapshot; catat history dan outbox dalam transaksi yang sama.
5. Worker mengirim pemberitahuan setelah commit. Kegagalan pengiriman tidak menggandakan invoice. Kanal pengiriman belum ditentukan; invoice tetap dapat ditampilkan/dicetak oleh aplikasi.
6. Saat pembayaran manual, kunci invoice, validasi belum lunas/void, dan catat pembayaran penuh beserta audit. Uang tunai diterima dan kembalian dibedakan dari nominal pelunasan.
7. Penyerahan dicatat tersendiri. Hindari penghapusan order/invoice berbayar. Refund dan pembatalan berbayar memerlukan fitur lanjutan, bukan mengubah status seenaknya.

### Contoh perhitungan yang harus dapat direpresentasikan

Layanan Cuci Setrika berharga contoh Rp7.000/kg, berat aktual 2,4 kg, minimum tagihan 3 kg. Jumlah tertagih 3 kg dan subtotal Rp21.000. Angka ini hanya ilustrasi; bukan seed harga.

Berat aktual tetap tersimpan sebagai 2,4 kg. Minimum, satuan, serta harga yang digunakan disnapshot. Penentuan pembulatan harus transparan; desain tidak diam-diam membulatkan semua berat ke kg penuh. Kuantitas PIECE harus berupa bilangan bulat walaupun tipe penyimpanannya desimal. Uang memakai `NUMERIC`/Java `BigDecimal`, bukan floating point.

### Antar-jemput dan stok

Permintaan PICKUP dapat dibuat sebelum order terbentuk; alamat/kontak/jadwal sudah diketahui, tetapi berat belum. Setelah barang diterima dan ditimbang, tautkan ke order yang tenant, outlet, dan pelanggannya cocok. DELIVERY harus merujuk order dan tidak boleh selesai sebelum barang siap diserahkan. Petugas dapat dicatat dari membership yang diizinkan, tanpa menambahkan role Kurir.

Persediaan memakai satuan dasar yang konsisten per item. Saldo merupakan proyeksi ledger mutasi. Jika menyimpan `inventory_balances`, kunci baris saldo, validasi saldo cukup, catat mutasi, dan perbarui saldo dalam satu transaksi. Koreksi dilakukan lewat mutasi penyesuaian/pembalikan yang diaudit. Pengurangan otomatis berdasarkan resep konsumsi laundry belum termasuk tahap ini.

## Normalisasi dan keamanan relasi

- Identitas user dipisahkan dari role tenant; harga paket dari paket; katalog layanan dari harga outlet; invoice dari payment.
- `tenant_id` dan `outlet_id` yang berulang merupakan pengulangan terkontrol untuk scope keamanan dan query. Composite foreign key memastikan nilainya sesuai dengan parent, bukan hanya menyimpan UUID yang kebetulan valid.
- Membership aktor, pelanggan, layanan, order, invoice, dan item stok tidak boleh menunjuk tenant lain. Relasi outlet-spesifik juga harus sama outlet.
- Composite FK melindungi integritas write; filter tenant dan otorisasi server tetap wajib untuk read maupun write. Database tidak mengetahui principal JWT hanya dari isi kolom.
- Unique constraint mencegah duplikasi nomor nota, key idempotency, event gateway, dan pembayaran penuh yang sama. Pemeriksaan ulang di bawah lock tetap diperlukan saat proses bersamaan.
- CHECK constraint membatasi nilai satu baris. Ia bukan pengganti aturan lintas tabel, validasi transisi status, atau pemeriksaan subscription.
- Audit, ledger, dan invoice final bersifat append-only secara kebijakan aplikasi dan hak akses database saat deployment. FK/CHECK saja tidak mencegah semua UPDATE/DELETE terhadap histori.
- Token publik, jika digunakan, acak dan memiliki scope minimal; simpan digest serta masa berlaku/revokasi. Token publik tidak memberikan hak kasir.
- Semua waktu transaksi menggunakan `TIMESTAMPTZ`; tampilkan mengikuti zona waktu outlet. Mata uang dan nilai yang dibayar harus cocok dengan invoice.

## Laporan dan prioritas pembangunan

Pisahkan laporan tagihan diterbitkan, tagihan belum dibayar, dan penerimaan kas. Laporan penerimaan memakai waktu pembayaran, bukan waktu order diterima. Untuk definisi penjualan berbayar awal, hanya invoice lunas dari order yang tidak dibatalkan dihitung. Pajak/biaya/diskon tetap dapat dijelaskan dari snapshot. Langganan platform tidak masuk omzet merchant.

Urutan penerapan yang disarankan:

1. Identitas global, membership, tenant/outlet, paket/langganan, dan onboarding.
2. Pelanggan, layanan/tarif, penerimaan dan penimbangan order.
3. Antrean/progres, READY, invoice, pembayaran, penyerahan, dan laporan dasar.
4. Antar-jemput dan persediaan dasar, sesuai entitlement paket yang dipilih.

## Keputusan produk yang masih terbuka

Harga paket, periode penagihan, jatah outlet/user, dan pembagian fitur belum ditetapkan. Gateway dan kanal notifikasi belum dipilih. Kebijakan saat langganan lewat jatuh tempo, masa tenggang, ekspor data, serta penanganan dua pembayaran gateway berbeda yang sama-sama berhasil perlu ditentukan sebelum implementasi billing diluncurkan. Data historis tidak dihapus ketika paket berakhir.

Aturan minimum/pembulatan berat, batas diskon kasir, SLA layanan, dan kebijakan penyerahan sebelum lunas perlu dipastikan saat desain layar serta acceptance criteria operasional dirinci. Struktur ini menyediakan data yang diperlukan tanpa menganggap seluruh pilihan produk tersebut sudah disepakati.

## Status verifikasi

Rancangan terdiri dari 33 tabel. Pemeriksaan sumber statis mencocokkan 78 referensi foreign key terhadap tabel, kolom, serta primary/unique key tujuannya, tanpa temuan referensi hilang. Ini bukan validasi sintaks penuh PostgreSQL. Rancangan juga ditinjau terhadap entity/migration lama dan batas tenant/transaksi. DDL belum dieksekusi pada PostgreSQL; belum ada verifikasi runtime, migration data, ataupun pengujian fitur. Tidak ada klaim fitur end-to-end siap diuji. Penerapan memerlukan migration baru dan perubahan aplikasi sesuai [pemetaan entity](legacy-mapping.md).

Kriteria yang perlu dibuktikan saat fitur terkait sudah diimplementasikan dan pengujian didelegasikan:

| Kasus | Hasil yang diharapkan |
|---|---|
| User memiliki dua merchant | Role dan akses outlet tidak terbawa dari merchant pertama ke merchant kedua. |
| UUID parent valid tetapi tenant/outlet berbeda | Penulisan relasi ditolak; pembacaan tidak membocorkan keberadaan data tenant lain. |
| Checkout/retry memakai key sama | Payload sama mengembalikan hasil yang sama; payload berbeda ditolak. |
| Webhook gateway duplikat atau tidak berurutan | Pembayaran diverifikasi dan aktivasi periode dilakukan sekali; status final tidak mundur oleh event lama. |
| Pembayaran paket sukses, profil belum lengkap | Owner dapat melanjutkan onboarding tanpa membayar kembali; akses operasional belum dibuka prematur. |
| Tarif layanan berubah sesudah penerimaan | Item lama tetap memakai snapshot; perubahan pesanan disengaja diaudit. |
| READY dikirim dua kali secara bersamaan | Hanya satu invoice aktif terbentuk dan satu event penerbitan logis dibuat. |
| Dua kasir melunasi invoice yang sama | Satu pelunasan diterima; permintaan lain idempotent atau ditolak, bukan menggandakan penerimaan kas. |
| PICKUP belum ditimbang | Job dapat disimpan tanpa order; tautan order kemudian harus cocok pelanggan/tenant/outlet. |
| Penyerahan selesai tetapi pembayaran belum ada | Tagihan masih terbuka; laporan tidak menganggapnya penjualan berbayar. |
| Dua pemakaian stok bersamaan | Ledger dan saldo tetap konsisten, kebijakan saldo tidak negatif ditegakkan. |
| Pembatalan order berbayar | Ditolak pada rilis pertama, tanpa menghapus histori. |
