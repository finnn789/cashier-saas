# Runbook operator: aktivasi paket Pro/Premium dan top-up kuota AI

Pemilik: orchestrator. Versi 2 (2026-11-14), TASK-082 (PRD v2.2 bagian 8.15, keputusan E1/E4) dan TASK-083 (add-on outlet tambahan, `subscriptions.extra_outlets`, keputusan M1-M3).
Untuk masa pilot: pembayaran lewat transfer manual. Sejak TASK-105/106 owner bisa membuat tagihan sendiri (`/merchant/subscription/checkout`, gateway MOCK), dan SUPERADMIN mengonfirmasi transfer lewat `/api/v1/platform` (lihat bagian 8). SQL di runbook ini tetap berlaku sebagai jalur darurat. Keterangan "belum ada integrasi payment gateway" sudah usang: sejak TASK-117 tersedia Midtrans (lihat bagian 9).

## Aturan sebelum menjalankan apa pun

1. **Verifikasi pembayaran dulu.** Jangan menjalankan SQL aktivasi atau top-up sebelum transfer diterima dan dicocokkan dengan tenant yang benar (nama usaha dan email pemilik). Catat bukti transfer di luar database.
2. Jalankan hanya di database yang dituju (cek `\conninfo`). Setiap pernyataan di bawah dibungkus `BEGIN ... COMMIT` dan dibatasi oleh `:tenant_id`. Periksa hasil `SELECT` di dalam transaksi, lalu `COMMIT`; bila ada yang janggal, ketik `ROLLBACK;`.
3. Jangan mengubah atau menghapus baris `FREETIER`, pesanan, shift, atau data lain. Runbook ini hanya menyentuh `subscriptions` (menambah/mengakhiri baris berbayar dan mengubah `extra_outlets` pada baris berbayar aktif) dan `ai_usage_periods` (bonus top-up). Outlet tidak pernah dihapus atau dinonaktifkan oleh operator.
4. Contoh memakai variabel psql. Isi dengan nilai nyata hanya di sesi Anda, jangan ditulis ke berkas ini atau ke chat:

```sql
\set tenant_id '<TENANT_UUID>'
\set owner_email '<EMAIL_PEMILIK_HURUF_KECIL>'
\set months 1
```

## Cara kerja paket (E1)

- Setiap tenant punya satu baris `FREETIER` berstatus `ACTIVE` dan `ends_at` kosong. **Baris ini tetap ada selamanya** sebagai dasar.
- Paket berbayar adalah baris tambahan (`PRO` atau `PREMIUM`) berstatus `ACTIVE` dengan `starts_at` dan `ends_at`.
- Paket efektif = paket tertinggi di antara baris yang aktif pada saat ini (`status = 'ACTIVE'`, `starts_at <= now()`, `ends_at` kosong atau `> now()`). Trial Premium 14 hari yang sedang berjalan (`tenants.premium_trial_started_at`) dihitung sebagai PREMIUM.
- **Kedaluwarsa otomatis turun paket**: setelah `ends_at` lewat, baris itu tidak lagi dihitung walaupun statusnya masih `ACTIVE`. Tidak perlu job penjadwal dan tidak ada data yang dihapus. Akses kasir (CASHIER) hanya berlaku selama paket efektif minimal PRO; saat paket naik lagi, akses pulih otomatis. Pemilik tetap bisa masuk di semua paket.
- Kolom `subscriptions` (V1) yang wajib diisi: `id`, `tenant_id`, `plan_code`, `status`, `version`, `created_at`, `updated_at`; `starts_at` dan `ends_at` boleh kosong menurut skema tetapi aplikasi menganggap baris tanpa `starts_at` tidak aktif, jadi selalu isi. `plan_code` dibatasi `FREETIER`, `PRO`, `PREMIUM` (V11). `id` memakai `gen_random_uuid()`; `version` mulai 0 (kolom optimistic lock JPA).
- Kuota AI: `ai_usage_periods` satu baris per tenant per bulan (`period_month` = tanggal 1 bulan itu menurut zona waktu tenant). Batas bulan = 300 + `bonus_questions`, batas harian 40 per tenant. Ringkasan harian tidak dihitung.

## 1. Cari tenant dari email pemilik

Email disimpan huruf kecil (`staff_users.email`).

```sql
SELECT t.id AS tenant_id, t.name, t.status, t.time_zone, t.premium_trial_started_at
FROM staff_users u
JOIN tenant_memberships m ON m.user_id = u.id AND m.role = 'OWNERMERCHANT' AND m.active
JOIN tenants t ON t.id = m.tenant_id
WHERE u.email = lower(:'owner_email');
```

Pastikan hasilnya satu baris dan namanya cocok dengan bukti transfer, lalu set `\set tenant_id '<uuid>'`.

## 2. Aktifkan PRO atau PREMIUM selama N bulan

Ganti `'PRO'` dengan `'PREMIUM'` bila perlu. Stop jika tenant sudah punya baris berbayar aktif: pakai bagian 3 (perpanjang) atau 4 (ganti paket), supaya tidak ada dua baris berbayar yang tumpang tindih.

```sql
BEGIN;

-- Harus 0 baris; kalau ada, ROLLBACK dan pakai bagian 3 atau 4.
SELECT id, plan_code, ends_at FROM subscriptions
WHERE tenant_id = :'tenant_id' AND plan_code <> 'FREETIER' AND status = 'ACTIVE'
  AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now());

INSERT INTO subscriptions (id, tenant_id, plan_code, status, starts_at, ends_at, created_at, updated_at, version)
SELECT gen_random_uuid(), t.id, 'PRO', 'ACTIVE', now(), now() + make_interval(months => :months), now(), now(), 0
FROM tenants t
WHERE t.id = :'tenant_id'
RETURNING id, plan_code, starts_at, ends_at;   -- harus 1 baris

COMMIT;
```

Konfirmasi dengan bagian 6.

## 3. Perpanjang baris berbayar yang masih aktif

Menambah N bulan ke `ends_at` baris berbayar aktif milik tenant (satu paket saja).

```sql
BEGIN;

UPDATE subscriptions
SET ends_at = ends_at + make_interval(months => :months), updated_at = now(), version = version + 1
WHERE tenant_id = :'tenant_id' AND plan_code <> 'FREETIER' AND status = 'ACTIVE'
  AND starts_at <= now() AND ends_at > now()
RETURNING id, plan_code, ends_at;              -- harus tepat 1 baris, kalau 0 atau 2+ lakukan ROLLBACK

COMMIT;
```

Bila baris berbayar sudah kedaluwarsa, bukan diperpanjang: lakukan bagian 2 (baris lama dibiarkan sebagai riwayat).

## 4. Ganti paket PRO menjadi PREMIUM (atau sebaliknya)

Akhiri baris lama sekarang, lalu buat baris baru. Sisa masa baris lama tidak dikonversi otomatis; tentukan N bulan baru sesuai pembayaran yang diterima.

```sql
BEGIN;

UPDATE subscriptions
SET ends_at = now(), updated_at = now(), version = version + 1
WHERE tenant_id = :'tenant_id' AND plan_code = 'PRO' AND status = 'ACTIVE'
  AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now())
RETURNING id, plan_code, ends_at;              -- harus tepat 1 baris

INSERT INTO subscriptions (id, tenant_id, plan_code, status, starts_at, ends_at, created_at, updated_at, version)
SELECT gen_random_uuid(), t.id, 'PREMIUM', 'ACTIVE', now(), now() + make_interval(months => :months), now(), now(), 0
FROM tenants t
WHERE t.id = :'tenant_id'
RETURNING id, plan_code, starts_at, ends_at;

COMMIT;
```

Untuk menurunkan PREMIUM ke PRO, tukar kedua nilai `plan_code`. Untuk berhenti berlangganan lebih awal cukup jalankan `UPDATE`-nya saja; tenant kembali ke `FREETIER` dan datanya tetap utuh.

## 5. Top-up +200 pertanyaan AI untuk bulan berjalan

Hanya untuk tenant dengan paket efektif PREMIUM yang kuota bulannya habis (atau hampir). Bonus berlaku untuk bulan ini saja dan tidak terbawa ke bulan depan. `period_month` dan `day_date` dihitung menurut zona waktu tenant. Bila baris bulan ini belum ada, baris dibuat dengan penghitung 0; bila sudah ada, hanya `bonus_questions` yang bertambah (penghitung dan token tidak diubah).

```sql
BEGIN;

INSERT INTO ai_usage_periods (tenant_id, period_month, questions_used, bonus_questions, day_date, day_questions, input_tokens, output_tokens, updated_at)
SELECT t.id,
       date_trunc('month', now() AT TIME ZONE t.time_zone)::date,
       0, 200,
       (now() AT TIME ZONE t.time_zone)::date,
       0, 0, 0, now()
FROM tenants t
WHERE t.id = :'tenant_id'
ON CONFLICT (tenant_id, period_month) DO UPDATE
SET bonus_questions = ai_usage_periods.bonus_questions + 200, updated_at = now()
RETURNING period_month, questions_used, bonus_questions;   -- batas bulan ini = 300 + bonus_questions

COMMIT;
```

Jangan menjalankan pernyataan ini dua kali untuk transfer yang sama. Top-up tidak menaikkan batas harian 40.

## 6. Periksa paket efektif, trial, dan pemakaian

```sql
-- Baris langganan (terbaru dulu) dan apakah sedang aktif
SELECT id, plan_code, status, starts_at, ends_at,
       (status = 'ACTIVE' AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now())) AS aktif_sekarang
FROM subscriptions
WHERE tenant_id = :'tenant_id'
ORDER BY starts_at DESC;

-- Paket efektif dan trial
SELECT t.id,
       CASE
         WHEN t.premium_trial_started_at IS NOT NULL AND now() < t.premium_trial_started_at + interval '14 days' THEN 'PREMIUM (trial)'
         ELSE COALESCE((
           SELECT s.plan_code FROM subscriptions s
           WHERE s.tenant_id = t.id AND s.status = 'ACTIVE' AND s.starts_at <= now() AND (s.ends_at IS NULL OR s.ends_at > now())
           ORDER BY CASE s.plan_code WHEN 'PREMIUM' THEN 3 WHEN 'PRO' THEN 2 ELSE 1 END DESC LIMIT 1), 'FREETIER')
       END AS paket_efektif,
       t.premium_trial_started_at,
       t.premium_trial_started_at + interval '14 days' AS trial_berakhir
FROM tenants t
WHERE t.id = :'tenant_id';

-- Pemakaian kuota AI (12 bulan terakhir)
SELECT period_month, questions_used, 300 + bonus_questions AS batas_bulan, bonus_questions,
       day_date, day_questions, input_tokens, output_tokens
FROM ai_usage_periods
WHERE tenant_id = :'tenant_id'
ORDER BY period_month DESC
LIMIT 12;
```

Catatan: trial Premium 14 hari dimulai otomatis saat tenant FREETIER menyelesaikan pesanan berbayar ke-50 dan hanya sekali per tenant; operator tidak perlu (dan tidak boleh) menyetel `premium_trial_started_at` secara manual.

## 7. Add-on outlet tambahan (Rp49.000/outlet/bulan)

Jatah outlet aktif per tenant = `1 + jumlah extra_outlets` dari semua baris berbayar (PRO/PREMIUM) yang aktif pada saat ini. Kolom `subscriptions.extra_outlets` (Flyway V12, `int NOT NULL DEFAULT 0`, `CHECK (extra_outlets >= 0)`) diisi operator pada baris berbayar yang aktif setelah transfer Rp49.000 x jumlah outlet x bulan diterima dan dicocokkan (aturan 1).

Prinsip:

- **FREETIER tidak boleh punya outlet tambahan.** Add-on hanya ada di baris `PRO`/`PREMIUM`; tenant tanpa baris berbayar aktif selalu berjatah 1 outlet. Jangan pernah mengisi `extra_outlets` pada baris `FREETIER`.
- **Add-on hidup dan berakhir bersama baris berbayarnya.** Tidak ada tanggal kedaluwarsa terpisah. Bila baris berbayar diperpanjang (bagian 3), add-on ikut diperpanjang; bila baris diakhiri atau kedaluwarsa, jatah kembali ke 1 + add-on baris lain yang masih aktif. Saat mengganti paket (bagian 4), salin nilai `extra_outlets` ke baris baru (isi kolom itu pada `INSERT`) atau add-on hilang.
- **Penurunan jatah tidak menghapus data.** Outlet aktif diurutkan menurut `created_at, id`; outlet urutan 1 selalu bisa dipakai. Outlet dengan urutan di atas jatah menjadi "melebihi jatah": operasional (login/pilih outlet, kasir, laporan, AI) ditolak HTTP 402 dan tidak ikut laporan gabungan, tetapi datanya utuh. Pemulihan: pemilik menonaktifkan outlet lain (menonaktifkan selalu boleh) sehingga yang tersisa muat dalam jatah, atau operator memperpanjang/menaikkan add-on, dan akses pulih otomatis tanpa perubahan data.
- Jangan mengubah `outlets.active` dari SQL; pemilik mengelolanya lewat aplikasi (nonaktif ditolak bila outlet aktif terakhir atau masih ada shift terbuka).

Isi `extra` dengan jumlah outlet tambahan TOTAL yang dibayar (bukan selisih):

```sql
\set extra 1
```

### 7a. Aktifkan atau ubah jumlah outlet tambahan

Berlaku untuk mengaktifkan N outlet tambahan pertama kali maupun mengganti jumlahnya (naik atau turun). Nilai `:extra` menggantikan nilai lama.

```sql
BEGIN;

UPDATE subscriptions
SET extra_outlets = :extra, updated_at = now(), version = version + 1
WHERE tenant_id = :'tenant_id' AND plan_code IN ('PRO', 'PREMIUM') AND status = 'ACTIVE'
  AND starts_at <= now() AND ends_at > now()
RETURNING id, plan_code, ends_at, extra_outlets;   -- harus tepat 1 baris, kalau 0 atau 2+ lakukan ROLLBACK

COMMIT;
```

Bila 0 baris: tenant belum punya paket berbayar aktif. Aktifkan PRO/PREMIUM dulu (bagian 2), lalu ulangi 7a. Konfirmasi dengan 7c.

### 7b. Hentikan add-on outlet (kembali ke 0)

```sql
BEGIN;

UPDATE subscriptions
SET extra_outlets = 0, updated_at = now(), version = version + 1
WHERE tenant_id = :'tenant_id' AND plan_code IN ('PRO', 'PREMIUM') AND status = 'ACTIVE'
  AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now()) AND extra_outlets > 0
RETURNING id, plan_code, ends_at, extra_outlets;   -- harus tepat 1 baris

COMMIT;
```

Outlet yang melebihi jatah baru langsung tidak bisa dipakai (402) tetapi tidak dihapus. Beri tahu pemilik agar menonaktifkan outlet yang tidak dipakai, atau perpanjang add-on.

### 7c. Periksa jatah outlet dan outlet yang bisa dipakai

```sql
-- Jatah: 1 + jumlah extra_outlets baris berbayar yang aktif sekarang
SELECT 1 + COALESCE(SUM(extra_outlets), 0) AS jatah_outlet
FROM subscriptions
WHERE tenant_id = :'tenant_id' AND plan_code IN ('PRO', 'PREMIUM') AND status = 'ACTIVE'
  AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now());

-- Outlet aktif menurut urutan created_at, id; dapat_dipakai = urutan <= jatah
WITH jatah AS (
  SELECT 1 + COALESCE(SUM(extra_outlets), 0) AS n
  FROM subscriptions
  WHERE tenant_id = :'tenant_id' AND plan_code IN ('PRO', 'PREMIUM') AND status = 'ACTIVE'
    AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now())
)
SELECT row_number() OVER (ORDER BY o.created_at, o.id) AS urutan,
       o.id AS outlet_id, o.name, o.time_zone, o.created_at,
       (row_number() OVER (ORDER BY o.created_at, o.id)) <= (SELECT n FROM jatah) AS dapat_dipakai
FROM outlets o
WHERE o.tenant_id = :'tenant_id' AND o.active
ORDER BY urutan;

-- Ringkas: jatah vs outlet aktif
SELECT (SELECT count(*) FROM outlets WHERE tenant_id = :'tenant_id' AND active) AS outlet_aktif,
       (SELECT 1 + COALESCE(SUM(extra_outlets), 0) FROM subscriptions
        WHERE tenant_id = :'tenant_id' AND plan_code IN ('PRO', 'PREMIUM') AND status = 'ACTIVE'
          AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now())) AS jatah_outlet;
```

Catatan: trial Premium 14 hari tidak menambah jatah outlet; jatahnya tetap 1 tanpa baris berbayar. Bila `outlet_aktif` lebih besar dari `jatah_outlet`, outlet berurutan di atas jatah sedang tidak bisa dipakai (402), dan itu perilaku yang benar.

## 8. Superadmin (panel platform, TASK-106)

Akun SUPERADMIN adalah akun `staff_users` biasa dengan `platform_admin = true` (Flyway V19). **Tidak ada endpoint yang membuat admin**; hanya operator yang menyetel kolom ini dengan SQL. Admin masuk lewat login biasa, lalu aplikasi memanggil `POST /api/v1/auth/platform` untuk mendapat token PLATFORM (hanya berlaku untuk `/api/v1/platform/**`). Status akun harus `ACTIVE` (profil bisnis sudah selesai atau akun sudah aktif).

Setiap permintaan platform membaca ulang kolom ini, jadi pencabutan berlaku seketika (permintaan berikutnya dijawab 404). Semua aksi tulis admin (beri/akhiri paket, top-up AI, tangguhkan/aktifkan tenant, tandai lunas/batalkan tagihan) wajib beralasan dan tercatat di `audit_logs` dengan admin sebagai pelaku (aksi berawalan `PLATFORM_`).

```sql
-- Jadikan admin (email huruf kecil); harus tepat 1 baris.
BEGIN;
UPDATE staff_users SET platform_admin = true, updated_at = now(), version = version + 1
WHERE email = lower(:'admin_email') AND status = 'ACTIVE'
RETURNING id, email, platform_admin;
COMMIT;

-- Cabut akses.
BEGIN;
UPDATE staff_users SET platform_admin = false, updated_at = now(), version = version + 1
WHERE email = lower(:'admin_email')
RETURNING id, email, platform_admin;
COMMIT;

-- Daftar admin saat ini.
SELECT id, email, full_name, status FROM staff_users WHERE platform_admin ORDER BY email;
```

Menangguhkan tenant mengubah `tenants.status` menjadi `SUSPENDED` tanpa menghapus data; pemilik dan kasir tidak bisa memilih outlet dan sesi mereka berhenti saat refresh berikutnya (paling lama selama masa berlaku token akses). Gunakan panel, bukan SQL, supaya tercatat di audit.

## 9. Midtrans (Snap, sandbox dulu)

Pembayaran langganan dan top-up AI bisa lewat Midtrans Snap (redirect). Perpanjangan = checkout baru tiap periode (tanpa auto-debit).

1. Dashboard sandbox Midtrans: Settings > Access Keys, salin Server Key dan Merchant ID. Jangan commit nilainya.
2. Set env backend: `CASHIER_BILLING_PROVIDER=MIDTRANS`, `MIDTRANS_SERVER_KEY`, `MIDTRANS_MERCHANT_ID`, `MIDTRANS_FINISH_URL` (mis. `http://localhost:5173/subscription`), `MIDTRANS_PRODUCTION=false`. Backend gagal start bila ada yang kosong saat provider `MIDTRANS`. Produksi: `MIDTRANS_PRODUCTION=true` dengan key produksi.
3. Dashboard: Settings > Payment > Notification URL = `https://<host-publik>/api/v1/webhooks/midtrans`. Endpoint ini publik; keabsahan dijamin signature SHA-512 dan pembacaan ulang status dari Midtrans sebelum tagihan dilunasi.
4. Uji lokal: buka tunnel (mis. `cloudflared tunnel --url http://localhost:8080` atau ngrok), pakai URL tunnel sebagai Notification URL, buat tagihan dari halaman Langganan, bayar di halaman sandbox, lalu cek tagihan berubah PAID.
5. Webhook dengan jumlah berbeda dari tagihan, atau pembayaran masuk setelah tagihan kedaluwarsa/dibatalkan, dicatat `gateway_events.processing_status = REJECTED` dan audit `PLATFORM_PAYMENT_FAILED`; operator harus refund manual lewat dashboard Midtrans.

## Pemecahan masalah

- Pemilik/kasir tidak melihat perubahan: token akses lama berlaku sampai masa berlakunya habis; minta pengguna refresh atau login ulang. Token kasir dibatasi sampai `ends_at` baris berbayar.
- Kasir ditolak setelah paket habis: ini perilaku yang benar (E2). Mengaktifkan PRO/PREMIUM kembali memulihkan akses tanpa mengubah keanggotaan.
- Respons HTTP 402 `Fitur ini tersedia di paket Pro.` berarti paket efektif di bawah yang dibutuhkan fitur itu; cek bagian 6.
- Respons HTTP 402 `Outlet tambahan memerlukan add-on outlet (Rp49.000/outlet/bulan).` saat membuat/mengaktifkan outlet atau memakai outlet berurutan di atas jatah: cek bagian 7c, lalu aktifkan/naikkan add-on (7a) setelah pembayaran atau minta pemilik menonaktifkan outlet.
- Constraint `ck_subscriptions_plan_code` menolak `plan_code` selain `FREETIER`, `PRO`, `PREMIUM`.
