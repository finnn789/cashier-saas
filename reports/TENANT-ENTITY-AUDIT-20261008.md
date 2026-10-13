# Audit TenantEntity — 8 Oktober 2026

Audit langsung oleh sesi utama atas permintaan pengguna, tanpa delegasi. Scope: entity, pemakaian kode produksi, kontrak DTO/settings, onboarding, dan SQL repository. Tidak ada perubahan aplikasi/migrasi/database atau eksekusi build/test. Database live tidak diperiksa. Working tree yang sudah berubah dipertahankan; temuan merujuk source saat audit, bukan hanya commit HEAD.

## Kesimpulan

TenantEntity memiliki 14 field persisten sendiri dan 4 kolom warisan (`id`, `created_at`, `updated_at`, `version`): total 18 kolom sesuai `V1__initial_schema.sql`. Jumlah ini sendiri bukan bukti overengineering atau pelanggaran normalisasi. Sebagian besar field dibutuhkan pengaturan bisnis dalam PRD 8.2. Masalah konkretnya ialah zona waktu yang disimpan pada dua tingkat tanpa pemakaian default yang jelas, metadata onboarding yang sebagian belum dimanfaatkan, dan jaminan idempotency yang belum lengkap pada skema saat ini.

Rekomendasi konservatif: jangan tambah tabel baru. Jadikan outlet sumber zona waktu, evaluasi penghapusan timestamp profil yang belum dipakai, pertahankan settings bisnis pada tenant. Fingerprint dapat disederhanakan setelah kontrak retry/data historis diperiksa. Jangan menghapus key idempotency atau relasi pembuat hanya untuk mengurangi jumlah kolom.

## Mengapa kolomnya bertambah

Entity menampung tiga tanggung jawab: identitas bisnis (`name`, `status`, `businessType`), settings bisnis (mata uang, logo, tarif, kontak, zona waktu), dan metadata pembuatan/onboarding (pembuat, key, fingerprint, waktu selesai). Penggabungan settings yang berhubungan satu-ke-satu dengan bisnis masih sederhana untuk MVP. Masing-masing kelompok tidak otomatis memerlukan entity atau tabel terpisah.

PRD 8.2 secara eksplisit meminta nama bisnis, logo, alamat, telepon, mata uang, zona waktu, pajak dan biaya layanan. PRD juga menyebut data transaksi terkait outlet dan waktu ditampilkan sesuai zona waktu outlet. Oleh karena itu menghapus semua settings dari tenant atau mengganti mata uang menjadi konstanta IDR akan mengubah kebutuhan produk.

## Penilaian setiap field

| Field | Bukti/penggunaan | Rekomendasi |
|---|---|---|
| `name` | Profil bisnis, settings dan respons onboarding | Pertahankan. Nama bisnis berbeda makna dari nama cabang. |
| `currency` | Settings, struk, guard perubahan sesudah ada transaksi | Pertahankan pada tenant; mata uang bisnis bukan konstanta global. |
| `timeZone` | Diisi default Jakarta dan dicerminkan saat update settings; pemakai waktu operasional membaca outlet | Kandidat penghapusan paling kuat; gunakan `OutletEntity.timeZone`. |
| `taxRate` | Menu dan checkout QR/POS, kemudian disnapshot ke order | Pertahankan. Tarif saat ini dan snapshot transaksi mempunyai fungsi berbeda. |
| `serviceChargeRate` | Menu dan checkout QR/POS, kemudian disnapshot ke order | Pertahankan bersama pajak; tidak perlu tabel tarif generik. |
| `logoUrl` | Settings dan respons mapper; diminta PRD | Pertahankan sebagai field opsional; tidak perlu tabel logo. |
| `address` | Profil bisnis, settings, pemeriksaan retry profil | Pertahankan untuk kontrak sekarang. Konsolidasi ke outlet hanya bila alamat bisnis tidak dimaksudkan berbeda dari alamat cabang. |
| `phoneNumber` | Profil bisnis dan settings | Sama dengan alamat; kontrak sekarang membedakan kontak bisnis dan kontak outlet. |
| `status` | Transisi PENDING_PROFILE ke ACTIVE dan pemeriksaan akses | Pertahankan. Tidak digantikan timestamp selesai. |
| `businessType` | Validasi FNB dan jenis bisnis aktif; data Laundry historis dipertahankan | Pertahankan FK; fokus FNB tidak membenarkan penghapusan klasifikasi historis. |
| `createdByUser` | Scope pencarian onboarding, key per user, dan pencarian profil pending | Pertahankan saat ini. Pembuat bukan selalu pemilik aktif; membership mengatur otorisasi. |
| `onboardingIdempotencyKey` | Lookup retry startFreeTier | Pertahankan; lengkapi keunikan pada database dan penanganan concurrent retry. |
| `onboardingRequestFingerprint` | Dibaca untuk membandingkan payload retry | Kandidat penyederhanaan, bukan field mati. Saat ini input hash selalu `FNB|FREETIER|1` setelah validasi. |
| `profileCompletedAt` | Ditulis ketika profil selesai; tidak ditemukan pembacaan di kode produksi | Kandidat penghapusan bila waktu milestone tidak diperlukan untuk audit/analitik. `updatedAt` bukan pengganti waktu selesai. |

Kolom warisan tetap dipertahankan: ID untuk identitas, timestamp untuk riwayat perubahan, dan version untuk optimistic locking.

## Temuan utama dan bukti

### 1. Zona waktu tenant belum menjadi default yang berguna

- `TenantEntity.java:30` menyimpan default `Asia/Jakarta`.
- `FreeTierOnboardingService.java:137` membuat outlet memakai zona waktu request; `completeFreeTierProfile` tidak mengubah zona waktu tenant. Outlet baru dengan zona waktu lain dapat langsung berbeda dari tenant.
- `MerchantBusinessSettingsService.java:55` dan `:57` menulis nilai zona waktu yang sama ke tenant dan outlet terpilih.
- `MerchantBusinessSettingsMapper.java:23`, struk, laporan penjualan dan viewer audit menggunakan zona waktu outlet.
- `docs/business-settings.md` menjelaskan pencerminan ke tenant default, tetapi pada source yang diperiksa tidak ditemukan konsumsi default tenant untuk pembuatan outlet atau fallback waktu.

Artinya ini duplikasi yang belum memiliki manfaat operasional terbukti. Untuk beberapa outlet, update outlet B juga mengganti tenant default sementara outlet A tetap pada zona waktunya. Ini bukan bukti semua zona waktu outlet seharusnya sama. Penyederhanaan: outlet saja menyimpan zona waktu; hapus parameter/pencerminan tenant dan selaraskan dokumentasi serta migrasi pada pekerjaan implementasi berikutnya.

### 2. Idempotency belum dijamin untuk request bersamaan

`FreeTierOnboardingService.java:70` mencari existing tenant, lalu `:88` melakukan persist bila belum ada. `TenantRepository.java:35` mencari berdasarkan `createdByUser.id` dan `onboardingIdempotencyKey`. Entity tidak mendeklarasikan unique constraint pasangan ini. SQL saat ini mempunyai primary key tenant serta FK pembuat, tetapi tidak ditemukan unique constraint/index onboarding.

Secara source, dua transaksi dapat sama-sama membaca belum ada dan membuat tenant berbeda. Membership unik per tenant/user tidak mencegah dua tenant berbeda. Ini risiko concurrency berdasarkan source, belum reproduksi runtime dan belum verifikasi constraint database live.

Perbaikan minimal yang direkomendasikan: keunikan `(created_by_user_id, onboarding_idempotency_key)` untuk record onboarding, lalu penanganan konflik concurrent insert dengan transaksi yang benar dan pemuatan hasil existing. Constraint saja tidak otomatis membuat respons retry berhasil; PostgreSQL membatalkan transaksi yang mengalami constraint violation, sehingga tidak cukup menangkap exception lalu query dalam transaksi yang sama. Scope key harus tetap per pembuat, bukan key global. Tidak memerlukan framework idempotency atau tabel baru untuk satu use case ini.

### 3. Fingerprint lebih rumit daripada variasi request yang aktif

`FreeTierOnboardingService.java:60`–`:64` hanya menerima FNB dan menghitung SHA-256 atas `typeCode + "|FREETIER|1"`. Semua request start yang lolos validasi sekarang memakai string yang sama. Hash masih dibaca dan dibandingkan pada `:72`, sehingga jangan menyebutnya tidak terpakai.

Untuk kontrak tetap ini, fingerprint berpotensi diganti pemeriksaan atribut request yang sudah tersimpan. Sebelum menghapus, periksa record historis, makna versi `1`, serta apakah tipe/plan bisa berubah setelah onboarding. Jika atribut dapat berubah, membandingkan keadaan tenant saat ini bukan pengganti aman untuk payload request awal. Tidak perlu membangun mekanisme generik sekarang; rekomendasi ini bersyarat.

### 4. Waktu profil selesai belum dikonsumsi

Pencarian kode produksi menemukan deklarasi dan penulisan `profileCompletedAt`, tanpa pembacaan untuk respons, query atau aturan bisnis. `status` mengendalikan alur profil. Timestamp bukan pelanggaran normalisasi dan berguna jika milestone onboarding memang perlu ditelusuri, tetapi kebutuhan itu belum terbukti dari penggunaan sekarang. Kandidat penghapusan kedua setelah zona waktu tenant; data historis tidak boleh dibuang sebelum kebutuhan retensi ditentukan.

### 5. Kontak bisnis dan outlet tidak boleh langsung dianggap duplikat

Onboarding menyalin satu alamat/telepon ke tenant dan outlet. Namun settings request/response sekarang memisahkan `address`/`phoneNumber` dari `outletAddress`/`outletPhoneNumber`, dan service memperbolehkan nilainya berbeda. Ada makna yang masuk akal: alamat/kontak bisnis dibanding alamat/kontak cabang.

Jika produk hanya membutuhkan kontak outlet, dua field tenant dapat dihapus dengan perubahan kontrak dan pemetaan data. Dengan kebutuhan/kontrak sekarang, penghapusan langsung belum dibenarkan. Menambah `tenant_contacts` untuk dua field juga belum diperlukan.

## Normalisasi yang proporsional

Normalisasi mengikuti ketergantungan dan arti data, bukan batas jumlah kolom. Audit tidak menemukan repeating group dalam potongan ini. Settings tunggal per tenant dapat tetap menjadi kolom tenant. Memecahnya menjadi `tenant_profile`, `tenant_settings`, `tenant_tax`, dan `tenant_contact` menambah join serta lifecycle tanpa kebutuhan cardinality yang terbukti.

Target tahap minimal, jika dua kandidat disetujui untuk implementasi: 12 field sendiri + 4 warisan = 16 kolom, dengan penghapusan `timeZone` dan `profileCompletedAt`. Jika fingerprint juga terbukti bisa dihapus tanpa mengubah retry, menjadi 11 + 4 = 15 kolom. Jumlah target ini bukan acceptance criterion; ketepatan tanggung jawab dan perilaku retry lebih penting.

Metadata onboarding boleh tetap melekat pada tenant untuk MVP dengan satu proses pembuatan. Tabel onboarding terpisah baru beralasan jika ada banyak attempt, expiry, kegagalan, atau riwayat workflow per tenant. Kebutuhan tersebut tidak ditambahkan dalam audit ini.

## Urutan tindak lanjut

1. Perbaiki jaminan concurrency idempotency pada schema dan service ketika implementasi diminta.
2. Hilangkan zona waktu tenant dan gunakan outlet sebagai sumber resmi.
3. Putuskan kebutuhan retensi waktu selesai profil; hapus hanya bila tidak diperlukan.
4. Evaluasi fingerprint terhadap kontrak retry dan record lama; tidak menghapus secara otomatis.
5. Pertahankan settings lain dan hindari tabel baru. Konsolidasi kontak membutuhkan keputusan makna bisnis terlebih dahulu.

Implementasi harus memperbarui entity, caller, DTO yang terdampak, dokumentasi dan migrasi secara konsisten. Migration yang sudah dipakai tidak boleh diubah sembarang; status deployment V1 belum diverifikasi. Snapshot tarif order historis tetap mempunyai tanggung jawab sendiri.

## Batas bukti

Codebase Memory dipakai untuk menemukan struktur dan Ruflo memory untuk konteks tambahan. Index graph lebih lama daripada source sekarang; semua kesimpulan substantif di atas diperiksa terhadap file aktual. PRD dan source menjadi bukti utama. `.ai/DECISIONS.md` tidak tersedia pada saat audit. Tidak ada agent dijalankan, test/build/startup atau koneksi database dilakukan. Audit selesai; rekomendasi belum diimplementasikan.
