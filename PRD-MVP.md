# Product Requirements Document — SaaS Cashier MVP

> Keputusan operasional aktif, 30 September 2026: backend F&B memakai Pay First wajib. QR outlet tidak memerlukan meja; QR meja tetap didukung. Owner/Cashier dapat membuat order. Order NEW/UNPAID hanya masuk produksi setelah pembayaran manual tercatat atomik sebagai CONFIRMED/PAID. Lifecycle berikutnya PREPARING -> READY -> COMPLETED, dan COMPLETED wajib READY + PAID. Owner saja menentukan diskon sebelum pajak/service charge. Owner dan Cashier boleh membatalkan NEW/UNPAID dengan alasan/audit; order dibayar tidak dibatalkan karena refund di luar scope. Push staf menggunakan WebSocket dengan REST resync/polling fallback, bukan SSE. Fokus tahap ini backend; frontend terpisah. Keputusan ini menggantikan bagian lama yang menyebut bayar setelah makan, meja wajib, atau konfirmasi sebelum pembayaran. Role aktif tetap SUPERADMIN, OWNERMERCHANT, CASHIER.

> Keputusan prioritas, 28 September 2026: F&B kembali menjadi produk pertama dan alur MVP di dokumen ini menjadi target implementasi aktif. Pengembangan Laundry ditunda; onboarding dan akses operasional Laundry dinonaktifkan tanpa menghapus data historis. Catatan arah Laundry di bawah dipertahankan sebagai riwayat keputusan.

> Catatan arah produk, 16 September 2026: pengguna menetapkan platform multi-bisnis dengan Laundry sebagai produk pertama. [Rancangan database Laundry v1](docs/database/README.md) mendokumentasikan scope dan model data baru untuk pekerjaan desain ini. Isi F&B di bawah dipertahankan sebagai referensi bisnis berikutnya; belum menjadi PRD laundry dan bukan tanda fitur laundry sudah diimplementasikan. Untuk desain database baru, keputusan laundry terbaru tersebut menggantikan batas scope F&B lama (termasuk langganan gateway, antar-jemput, dan inventory dasar).

> Keputusan Freetier, 23 September 2026: Freetier adalah paket gratis untuk pencatatan kasir biasa. Kasir memasukkan transaksi dan nama pelanggan secara manual, lalu mencatat uang yang diterima dari pelanggan secara manual. Data transaksi disimpan di database SaaS dan dapat dibuka kembali setelah login, termasuk dari perangkat lain yang diizinkan. Aktivasi Freetier tidak memerlukan invoice atau pembayaran langganan melalui gateway platform. Batas fitur lain masih perlu dipastikan; aturan F&B di bawah tidak otomatis menjadi hak Freetier Laundry.

> Keputusan implementasi, 6 Oktober 2026: lanjutkan backend POS F&B, pembayaran Pay First, proses dapur, struk, dan laporan penjualan. Payment gateway memakai mock terlebih dahulu dengan hasil tertunda, berhasil, atau gagal; integrasi provider nyata belum termasuk tugas ini. Pembayaran berhasil tetap menjadi syarat masuk produksi. Frontend tetap terpisah.

**Versi:** 0.1
**Status:** Draft awal  
**Tanggal:** 13 September 2026  
**Fokus:** MVP untuk bisnis F&B skala kecil dengan 1–3 outlet

## 1. Ringkasan Produk

SaaS Cashier adalah aplikasi kasir dan manajemen pesanan berbasis web untuk kafe, restoran, dan usaha F&B kecil. Produk menyatukan pesanan yang dibuat oleh kasir dan pesanan mandiri pelanggan melalui QR meja ke dalam satu antrean operasional.

Pemilik dan staf menggunakan dashboard pada komputer, tablet, atau ponsel. Pelanggan cukup memindai QR dan memesan melalui browser tanpa memasang aplikasi.

## 2. Masalah yang Diselesaikan

Bisnis F&B kecil sering menghadapi masalah berikut:

- Pesanan dari kasir dan meja dicatat melalui alur yang berbeda.
- Pesanan lisan atau tulisan tangan rentan salah dan hilang.
- Kasir dan dapur tidak memiliki status pesanan yang sama.
- Pelanggan harus menunggu pelayan untuk melihat menu atau memesan ulang.
- Pemilik sulit mengetahui omzet, transaksi, dan produk terlaris secara cepat.
- Aplikasi POS yang lengkap sering terlalu rumit atau mahal untuk bisnis kecil.

## 3. Visi MVP

Memberikan satu sistem sederhana yang memungkinkan bisnis menerima pesanan dari QR meja dan kasir, memprosesnya melalui antrean yang sama, menyelesaikan pembayaran, dan melihat laporan penjualan dasar.

## 4. Sasaran MVP

- Kasir dapat membuat dan menyelesaikan transaksi dengan cepat.
- Pelanggan dapat melihat menu dan mengirim pesanan melalui QR tanpa login.
- Pesanan QR dan kasir muncul pada dashboard pesanan yang sama secara real-time.
- Staf dapat melacak pesanan dari baru hingga selesai.
- Pemilik dapat mengatur menu, meja, pengguna, pajak, dan biaya layanan.
- Pemilik dapat melihat ringkasan penjualan harian dan riwayat transaksi.
- Sistem mendukung banyak bisnis dengan data yang terisolasi per tenant.

## 5. Di Luar Cakupan MVP

Fitur berikut ditunda setelah MVP:

- Inventori bahan baku dan resep.
- Loyalty, membership, poin, dan voucher kompleks.
- Integrasi marketplace atau layanan delivery.
- Akuntansi lengkap.
- Manajemen supplier dan pembelian.
- Reservasi meja.
- Payroll dan penjadwalan pegawai.
- Analitik atau rekomendasi berbasis AI.
- Dukungan operasi offline penuh.
- Integrasi payment gateway otomatis.
- Integrasi WhatsApp.
- Multi-outlet tingkat lanjut dan laporan konsolidasi kompleks.

## 6. Target Pengguna

### 6.1 Pemilik/Owner

Mengelola konfigurasi bisnis, pengguna, menu, meja, serta memantau transaksi dan laporan.

### 6.2 Admin/Manager

Membantu mengelola menu dan operasional, tetapi tidak memiliki seluruh kewenangan pemilik.

### 6.3 Kasir

Membuat pesanan langsung, menerima pembayaran, mengelola tagihan, dan melihat antrean pesanan.

### 6.4 Staf Dapur

Melihat antrean produksi dan memperbarui status pesanan.

### 6.5 Pelanggan

Memindai QR, melihat menu, membuat pesanan, dan memantau status pesanan tanpa membuat akun.

## 7. Proposisi Nilai

- Satu antrean untuk pesanan QR dan kasir.
- Pelanggan tidak perlu memasang aplikasi atau membuat akun.
- Pengoperasian sederhana untuk bisnis F&B kecil.
- Status pesanan terlihat oleh kasir, dapur, dan pelanggan.
- Pemilik memperoleh data penjualan dasar tanpa pencatatan manual.

## 8. Ruang Lingkup Fungsional MVP

### 8.1 Autentikasi dan Tenant

- Pengguna staf dapat masuk dan keluar menggunakan email serta kata sandi.
- Setiap bisnis menjadi satu tenant dengan data terisolasi.
- Pemilik dapat mengundang atau membuat akun staf.
- Sistem memiliki peran Owner, Manager, Cashier, dan Kitchen.
- Pengguna hanya dapat mengakses outlet dan fungsi yang diizinkan.

### 8.2 Pengaturan Bisnis dan Outlet

- Owner dapat mengatur nama bisnis, logo, alamat, nomor telepon, mata uang, zona waktu, pajak, dan biaya layanan.
- MVP mendukung setidaknya satu outlet per bisnis.
- Data pesanan dan transaksi selalu terkait dengan outlet.

### 8.3 Manajemen Menu

- Owner atau Manager dapat membuat, mengubah, mengarsipkan, dan mengurutkan kategori.
- Owner atau Manager dapat membuat, mengubah, mengarsipkan, dan mengurutkan produk.
- Produk memiliki nama, deskripsi, harga, foto opsional, kategori, dan status tersedia/habis.
- Produk dapat memiliki grup pilihan sederhana, misalnya ukuran, level pedas, atau topping.
- Pilihan dapat diwajibkan atau opsional serta dapat menambah harga.
- Produk yang habis tetap terlihat dengan label “Habis” tetapi tidak dapat ditambahkan ke keranjang.

### 8.4 Manajemen Meja dan QR

- Owner atau Manager dapat membuat dan mengubah meja.
- Setiap meja memiliki nama/nomor dan QR unik.
- QR mengarah ke menu outlet serta membawa identitas meja.
- QR dapat dilihat dan diunduh untuk dicetak.
- Meja dapat berstatus kosong, aktif, atau menunggu pembayaran.
- QR harus menggunakan token publik yang tidak membuka ID internal berurutan.

### 8.5 Menu dan Pemesanan Pelanggan

- Pelanggan dapat membuka menu tanpa login.
- Pelanggan dapat melihat kategori, produk, harga, foto, dan ketersediaan.
- Pelanggan dapat memilih varian/tambahan dan menulis catatan per item.
- Pelanggan dapat menambah, mengubah, dan menghapus item dari keranjang.
- Sebelum mengirim pesanan, pelanggan melihat subtotal, pajak, biaya layanan, dan total.
- Pelanggan memasukkan nama atau label pemesan singkat.
- Pelanggan dapat memilih “bayar di kasir” atau “bayar setelah makan”.
- Setelah dikirim, pesanan memperoleh nomor pesanan yang mudah dibaca.
- Pelanggan dapat melihat status pesanan melalui halaman yang sama.
- Pesanan pelanggan tidak langsung dianggap dibayar.

### 8.6 POS Kasir

- Kasir dapat membuat pesanan dine-in atau takeaway.
- Untuk dine-in, kasir dapat memilih meja.
- Kasir dapat mencari atau memilih produk berdasarkan kategori.
- Kasir dapat menambahkan pilihan produk, jumlah, dan catatan.
- Kasir dapat melihat subtotal, pajak, biaya layanan, diskon nominal/persentase, dan total.
- Kasir dapat menahan pesanan sebagai tagihan aktif.
- Kasir dapat memilih pembayaran tunai atau pembayaran non-tunai manual.
- Untuk pembayaran tunai, sistem menghitung uang kembali.
- Setelah pembayaran berhasil dicatat, transaksi memiliki nomor struk.
- Sistem menyediakan tampilan struk yang dapat dicetak dari browser.

### 8.7 Manajemen Pesanan

- Semua pesanan QR dan kasir tampil dalam satu daftar.
- Pesanan menunjukkan nomor, sumber, meja/tipe layanan, waktu, item, catatan, total, status pesanan, dan status pembayaran.
- Status pesanan: `NEW`, `CONFIRMED`, `PREPARING`, `READY`, `COMPLETED`, dan `CANCELLED`.
- Status pembayaran: `UNPAID`, `PAID`, dan `REFUNDED` sebagai persiapan data; proses refund otomatis belum termasuk MVP.
- Staf berwenang dapat memperbarui status pesanan.
- Pembatalan wajib memiliki alasan dan tercatat dalam audit log.
- Perubahan pesanan yang telah dikirim harus tercatat agar total dan item tidak berubah tanpa jejak.
- Pesanan baru harus muncul tanpa pengguna memuat ulang halaman secara manual.

### 8.8 Tampilan Dapur

- Staf dapur melihat pesanan aktif dalam bentuk kartu atau kolom status.
- Tampilan menonjolkan nomor pesanan, waktu tunggu, item, jumlah, dan catatan.
- Dapur dapat mengubah status dari dikonfirmasi menjadi diproses lalu siap.
- Harga dan informasi pembayaran tidak perlu ditampilkan pada layar dapur.
- Pesanan selesai atau dibatalkan keluar dari antrean aktif.

### 8.9 Pembayaran dan Penutupan Pesanan

- MVP mencatat pembayaran tunai dan non-tunai secara manual.
- Satu pesanan hanya dianggap lunas setelah pembayaran dikonfirmasi kasir.
- Sistem menyimpan jumlah dibayar, metode, waktu, dan pengguna penerima pembayaran.
- Split bill, cicilan, dan beberapa metode pembayaran dalam satu transaksi tidak termasuk MVP.
- Setelah pesanan selesai dan lunas, meja kembali kosong jika tidak ada tagihan aktif lain.

### 8.10 Laporan Dasar

- Ringkasan hari ini: omzet bersih, jumlah transaksi, nilai transaksi rata-rata, dan pesanan dibatalkan.
- Daftar transaksi dapat difilter berdasarkan tanggal, status, dan metode pembayaran.
- Laporan produk terlaris berdasarkan jumlah terjual.
- Rekap pembayaran tunai dan non-tunai.
- Data laporan hanya menghitung transaksi berstatus dibayar dan tidak dibatalkan.
- Owner dapat mengekspor daftar transaksi ke CSV; ekspor lanjutan ditunda.

### 8.11 Audit Minimum

- Sistem mencatat pengguna dan waktu untuk tindakan penting: perubahan harga, pembatalan pesanan, pemberian diskon, dan pencatatan pembayaran.
- Owner dapat melihat audit log sederhana.

## 9. Alur Utama

### 9.1 Pesanan Melalui QR

1. Pelanggan memindai QR meja.
2. Sistem membuka menu outlet dan mengenali meja.
3. Pelanggan memilih item, pilihan tambahan, jumlah, dan catatan.
4. Pelanggan meninjau total lalu mengirim pesanan.
5. Sistem membuat pesanan berstatus `NEW` dan pembayaran `UNPAID`.
6. Kasir mengonfirmasi pesanan menjadi `CONFIRMED`.
7. Dapur mengubah status menjadi `PREPARING`, lalu `READY`.
8. Pesanan disajikan dan kasir mencatat pembayaran.
9. Pesanan ditutup sebagai `COMPLETED`.

### 9.2 Pesanan Langsung di Kasir

1. Kasir memilih tipe layanan dan meja jika diperlukan.
2. Kasir menambahkan item dan catatan.
3. Kasir mengirim pesanan ke antrean.
4. Dapur memproses pesanan dengan alur status yang sama.
5. Kasir menerima dan mencatat pembayaran.
6. Pesanan ditutup sebagai `COMPLETED` dan struk dapat dicetak.

## 10. Aturan Bisnis Penting

- Harga item pada pesanan disalin sebagai snapshot agar perubahan menu tidak mengubah transaksi lama.
- Total dihitung oleh server, bukan dipercaya dari browser pelanggan.
- Nomor pesanan mudah dibaca tetapi bukan pengganti ID internal.
- Hanya pengguna berwenang yang dapat memberi diskon atau membatalkan pesanan.
- Pesanan yang sudah dibayar tidak boleh dihapus.
- Semua waktu disimpan secara konsisten dan ditampilkan sesuai zona waktu outlet.
- Data dari satu tenant tidak boleh dapat dibaca atau diubah tenant lain.
- Halaman pelanggan hanya boleh mengakses data publik yang diperlukan melalui token aman.
- Perubahan status harus divalidasi; misalnya pesanan batal tidak dapat diproses kembali tanpa tindakan khusus.

## 11. Entitas Data Inti

- Tenant/Business
- Outlet
- User
- Role/Permission
- Category
- Product
- OptionGroup
- ProductOption
- Table
- TableQrToken
- Order
- OrderItem
- OrderItemOption
- Payment
- Discount
- AuditLog

Setiap entitas operasional utama wajib memiliki `tenant_id`; entitas terkait outlet juga memiliki `outlet_id`.

## 12. Kebutuhan Nonfungsional

### Keamanan

- Kata sandi disimpan menggunakan hashing yang aman.
- Seluruh lalu lintas produksi menggunakan HTTPS.
- Otorisasi diperiksa di server untuk setiap tindakan staf.
- Endpoint publik QR dibatasi dan divalidasi untuk mencegah penyalahgunaan.
- Data sensitif tidak dimasukkan ke log aplikasi.

### Kinerja

- Halaman menu dan dashboard utama ditargetkan tampil dalam kurang dari 3 detik pada koneksi normal.
- Perubahan pesanan ditargetkan terlihat oleh dashboard lain dalam kurang dari 5 detik.
- Pencarian atau pemilihan produk POS harus terasa instan untuk katalog bisnis kecil.

### Keandalan

- Pembuatan pesanan harus idempotent untuk mencegah pesanan ganda akibat klik ulang atau jaringan lambat.
- Nominal pembayaran dan total transaksi menggunakan tipe desimal/integer terkecil, bukan floating point.
- Kesalahan operasional harus menghasilkan pesan yang dapat dipahami pengguna.

### Pengalaman Pengguna

- Antarmuka pelanggan responsif dan mengutamakan ponsel.
- POS dapat digunakan pada layar desktop dan tablet.
- Tombol aksi utama jelas dan sesuai peran pengguna.
- Status tidak hanya dibedakan berdasarkan warna.

## 13. Kriteria Keberhasilan MVP

MVP dianggap layak diuji ke pengguna ketika:

- Satu bisnis dapat mendaftar dan menyiapkan outlet, menu, meja, serta akun staf.
- Pelanggan berhasil memesan melalui QR tanpa login.
- Kasir berhasil membuat pesanan langsung.
- Kedua sumber pesanan masuk ke antrean yang sama tanpa duplikasi.
- Dapur dapat memproses dan memperbarui status pesanan.
- Kasir dapat mencatat pembayaran dan mencetak struk.
- Owner dapat melihat transaksi dan laporan harian yang konsisten.
- Pengujian membuktikan pengguna tenant A tidak dapat mengakses data tenant B.

Target validasi bisnis awal:

- Digunakan oleh minimal 3 bisnis pilot.
- Minimal 100 transaksi end-to-end berhasil diproses.
- Tingkat keberhasilan pembuatan pesanan minimal 99% selama pilot.
- Waktu median kasir membuat pesanan sederhana di bawah 60 detik setelah pelatihan singkat.

## 14. Acceptance Criteria Tingkat Produk

### AC-01 — Isolasi Tenant

Jika pengguna berasal dari bisnis A, seluruh permintaan untuk membaca atau mengubah data bisnis B harus ditolak dan tidak membocorkan keberadaan data tersebut.

### AC-02 — Pesanan QR

Jika QR valid dan produk tersedia, pelanggan dapat mengirim keranjang lalu menerima nomor pesanan serta status awal tanpa login.

### AC-03 — Harga Konsisten

Total yang tersimpan harus dihitung ulang di server berdasarkan harga aktif, pilihan, pajak, biaya layanan, dan diskon yang sah.

### AC-04 — Antrean Terpadu

Pesanan baru dari QR maupun POS harus tampil pada daftar operasional dan layar dapur dengan sumber yang dapat dibedakan.

### AC-05 — Pembayaran

Ketika kasir mencatat pembayaran valid, status pembayaran berubah menjadi `PAID`, informasi pembayaran tersimpan, dan transaksi masuk ke laporan.

### AC-06 — Pembatalan

Pembatalan hanya dapat dilakukan pengguna berwenang, wajib memiliki alasan, dan menghasilkan audit log.

### AC-07 — Ketersediaan Produk

Produk berstatus habis tidak dapat dipesan oleh pelanggan atau kasir, termasuk jika status berubah sesaat sebelum checkout.

## 15. Metrik Produk yang Dicatat

- Jumlah bisnis dan outlet aktif.
- Jumlah pesanan berdasarkan sumber QR atau POS.
- Rasio pesanan berhasil, gagal, dan dibatalkan.
- Waktu dari `NEW` ke `CONFIRMED`.
- Waktu dari `CONFIRMED` ke `READY`.
- Nilai transaksi rata-rata.
- Produk terlaris.
- Persentase pembayaran tunai dan non-tunai.

## 16. Tahapan Implementasi MVP

### Milestone 1 — Fondasi

Tenant, autentikasi, peran, outlet, pengaturan bisnis, dan struktur audit.

### Milestone 2 — Katalog dan Meja

Kategori, produk, pilihan produk, ketersediaan, meja, token QR, dan menu publik.

### Milestone 3 — Pemesanan

Keranjang pelanggan, POS kasir, kalkulasi harga server-side, antrean terpadu, dan status real-time.

### Milestone 4 — Operasional dan Pembayaran

Tampilan dapur, pencatatan pembayaran, penutupan meja, dan struk browser.

### Milestone 5 — Laporan dan Pilot

Dashboard, riwayat transaksi, ekspor CSV, audit log, pengujian keamanan tenant, dan persiapan bisnis pilot.

## 17. Risiko MVP dan Mitigasi

- **Pesanan ganda:** gunakan idempotency key saat checkout.
- **Harga dimanipulasi browser:** hitung ulang seluruh total di server.
- **Kebocoran antar-tenant:** wajibkan filter tenant pada akses data dan tambahkan automated test.
- **Koneksi tidak stabil:** pertahankan keranjang lokal dan tampilkan status pengiriman yang jelas; offline penuh ditunda.
- **Antrean tidak sinkron:** gunakan pembaruan real-time dengan fallback polling.
- **QR disalahgunakan:** gunakan token acak, rate limiting, dan opsi regenerasi QR.
- **Lingkup membesar:** fitur di luar bagian 8 tidak masuk sprint MVP tanpa keputusan produk baru.

## 18. Keputusan yang Masih Terbuka

- Nama final produk dan identitas merek.
- Apakah satu tenant dapat memiliki lebih dari satu outlet pada rilis MVP pertama.
- Apakah pesanan QR wajib dikonfirmasi kasir atau dapat langsung masuk dapur.
- Batas nilai diskon untuk peran Cashier dan Manager.
- Printer yang diprioritaskan untuk pilot.
- Model harga berlangganan dan masa uji coba.
- Teknologi aplikasi, basis data, hosting, dan layanan real-time.

## 19. Prinsip Penjagaan Scope

Setiap fitur baru hanya masuk MVP jika secara langsung diperlukan untuk menyelesaikan alur berikut:

`buat menu → buat meja/QR → pelanggan atau kasir membuat pesanan → dapur memproses → kasir menerima pembayaran → owner melihat laporan`.

Jika sebuah fitur tidak diperlukan untuk alur tersebut, fitur ditempatkan pada backlog setelah MVP.
