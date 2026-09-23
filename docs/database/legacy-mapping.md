# Pemetaan entity lama ke rancangan laundry

Status: fondasi identitas diterapkan melalui Flyway V4; pemetaan operasional Laundry lain masih rencana. Dibuat 16 September 2026, diperbarui 23 September 2026.

Dokumen ini membaca model yang saat ini ada di `src/main/java/org/lite/project/cashiersaas/entity` dan migration Flyway V1/V2. Nama target merujuk pada [rancangan utama](README.md) dan [SQL referensi](laundry-v1.sql).

## Pemetaan seluruh entity

| Entity saat ini | Arah rancangan | Alasan |
|---|---|---|
| `TenantEntity` | Pertahankan konsep `tenants`, tambah jenis bisnis, slug, dan status onboarding | Merchant adalah tenant yang sama, tidak perlu dua identitas bisnis paralel. Profil dapat belum lengkap ketika checkout paket dimulai. |
| `StaffUserEntity` | Identitas akun global dengan `tenant_memberships` | Akun dapat ada sebelum merchant dan dapat bergabung ke beberapa merchant. Role merupakan hak pada merchant tertentu. |
| `StaffUserOutletAccessEntity` | Pindahkan hubungan ke membership dan outlet | Hak outlet tidak boleh diberikan kepada user tanpa keanggotaan tenant terkait. |
| `OutletEntity` | Pertahankan `outlets` dengan parent tenant yang jelas | Alamat, kontak, dan zona waktu operasional melekat pada outlet. |
| `CategoryEntity` | Gunakan kategori layanan laundry opsional; simpan kategori menu lama sebagai data F&B | Pengelompokan Cuci/Setrika/Khusus tidak berarti data kategori makanan lama boleh otomatis dikonversi. |
| `ProductEntity` | Gunakan `laundry_services` untuk layanan laundry | Jasa per kg/potong memiliki aturan satuan dan minimum tagihan, bukan produk makanan. Data produk lama tidak otomatis dikonversi. |
| `OptionGroupEntity` | Simpan sebagai referensi pengembangan F&B | Ukuran minuman dan topping bukan kebutuhan inti laundry pertama. Regular/express dapat menjadi layanan berbeda. |
| `ProductOptionEntity` | Simpan sebagai referensi pengembangan F&B | Belum perlu mesin modifier umum untuk laundry. |
| `DiningTableEntity` | Tidak digunakan oleh modul laundry | Meja restoran bukan antrian atau mesin cuci. |
| `TableQrTokenEntity` | Tidak digunakan oleh modul laundry | Link tagihan publik memiliki token dan otorisasi sendiri; tidak memakai token meja. |
| `SalesOrderEntity` | Pisahkan identitas umum di `orders` dari detail `laundry_orders` | `DINE_IN`, `TAKEAWAY`, meja dan antrean dapur tidak sesuai dengan proses cucian. |
| `OrderItemEntity` | Gunakan `laundry_order_items` | Kuantitas desimal, satuan, berat aktual, jumlah tertagih, minimum, serta snapshot tarif diperlukan. |
| `OrderItemOptionEntity` | Tunda untuk F&B | Laundry pertama memakai item layanan bertipe jelas. |
| `PaymentEntity` | Pembayaran pelanggan menjadi `customer_payments`; pembayaran langganan berada di domain platform | Dua aliran uang memiliki penerima dan siklus hidup berbeda. Nominal yang melunasi tagihan dibedakan dari uang tunai diterima dan kembalian. |
| `DiscountEntity` | Nilai/alasan/aktor diskon disnapshot pada tagihan; jejak perubahan di audit | Satu diskon sederhana tidak memerlukan katalog promosi atau tabel voucher. |
| `AuditLogEntity` | Pertahankan audit, sesuaikan aktor menjadi membership tenant | Jejak harga, diskon, pembatalan, pembayaran, dan stok harus konsisten dengan scope tenant. |
| `BaseEntity` | Pertahankan pola UUID dan waktu pembuatan | UUID tetap identifier internal, bukan bukti otorisasi akses publik. |
| `MutableBaseEntity` | Pertahankan waktu perubahan, tambahkan optimistic version pada agregat mutable | Data yang dapat berubah perlu perlindungan konkurensi; ledger dan snapshot final tidak disunting bebas. |

`staff_user_roles` saat ini merupakan collection table, bukan entity terpisah. Target rilis pertama menyimpan satu role merchant pada tiap membership: kode `OWNERMERCHANT` ditampilkan sebagai Owner, atau `CASHIER`. Tidak perlu tabel role/permission dinamis untuk dua role ini. `SUPERADMIN` adalah akses platform terpisah dan tidak dimasukkan sebagai membership merchant biasa.

## Dampak autentikasi yang perlu diimplementasikan bersama migration

`StaffUserEntity` menjadi identitas tunggal. `TenantMembershipEntity` menyimpan hubungan tenant serta role, sehingga penerbitan token operasional mengambil tenant dan role dari membership aktif. Sistem memiliki dua konteks:

1. Akun terautentikasi yang belum memilih atau belum memiliki tenant: hanya dapat mengakses alur akun, pembelian paket, dan onboarding miliknya.
2. Akun dengan membership aktif pada tenant yang dipilih: server memverifikasi membership, role, akses outlet, serta status akses bisnis, lalu menerbitkan token operasional untuk tenant tersebut.

Tenant pada request biasa tetap berasal dari principal yang telah divalidasi. Memilih merchant ketika login tidak berarti tenant ID dari body boleh dipercaya pada endpoint bisnis. Token sebelum onboarding tidak boleh diterima sebagai token kasir. Perubahan password/user identity, membership, JWT, dan repository perlu dikirim dalam satu tahap yang konsisten; dokumen desain ini tidak mengubah komponen tersebut.

## Strategi perpindahan data

SQL referensi adalah definisi target untuk database kosong yang terpisah. SQL tersebut bukan V3 untuk dijalankan pada database saat ini, karena beberapa nama tabel sama tetapi bentuknya berubah.

Sebelum membuat migration penerapan:

1. Inventarisasi database yang benar-benar dipakai, versi Flyway, isi tabel, dan backup yang dapat dipulihkan. Keberadaan `target/` tidak membuktikan ada atau tidaknya data bisnis.
2. Pertahankan V1/V2 yang mungkin sudah diterapkan; jangan mengedit checksum migration lama.
3. Buat migration baru secara bertahap untuk identitas, membership, billing, dan operasional laundry. Untuk tabel yang bentuknya berubah, gunakan kolom/tabel transisi serta backfill yang dapat diverifikasi.
4. Pindahkan data `users` ke `staff_users` sebagai identitas global dan pertahankan membership. Tinjau `SUPERADMIN` secara eksplisit. Pertahankan password hash yang valid, identitas aktor historis, dan konsistensi subject JWT; jangan menganggap semua akun sebagai Owner.
5. Pertahankan outlet dan hubungan tenant. Jangan menganggap transaksi F&B lama sebagai cucian atau menghapus riwayat berbayar.
6. Deploy mapping JPA, repository, autentikasi, dan migration secara selaras. Verifikasi login, tenant isolation, invoice, dan pembayaran sebelum menonaktifkan jalur lama.

Jika database ternyata hanya disposable development, fresh database merupakan alternatif deployment tersendiri setelah kondisi tersebut diverifikasi. Dokumen ini tidak menghapus atau mereset data.

## Perluasan F&B nanti

Fondasi akun/tenant/langganan, identitas order, invoice, pembayaran, dan audit dapat digunakan kembali. Modul F&B menambahkan katalog menu, meja/QR, detail order F&B, item/options, serta status dapur. Tambahkan discriminator dan constraint subtype secara eksplisit ketika bisnis kedua diluncurkan; jangan membuka tipe bisnis di database sebelum modulnya siap.

Riwayat laundry tidak berubah menjadi data F&B, dan nominal tagihan historis tetap berasal dari snapshot. Rancangan pertama tidak memuat tabel operasional untuk retail, jasa, online seller, atau distributor.
