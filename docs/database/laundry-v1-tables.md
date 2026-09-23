# Kamus Data Laundry V1

Status: desain referensi untuk PostgreSQL, belum diterapkan. Berkas SQL pasangannya hanya untuk database kosong terpisah; ia bukan migrasi Flyway dan tidak mengubah skema F&B yang sedang berjalan.

## Konvensi dan batasan

- Semua ID adalah UUID yang dibuat aplikasi. Waktu disimpan `TIMESTAMPTZ`; uang `NUMERIC(19,2)`; jumlah/berat `NUMERIC(14,3)`.
- `tenant_id` selalu diambil dari principal terautentikasi. FK komposit `(tenant_id, id)` mencegah hubungan lintas tenant; akses outlet tetap diperiksa aplikasi melalui `membership_outlet_access`.
- `version` disediakan untuk optimistic locking. Kolom snapshot tidak boleh diperbarui setelah transaksi bisnis diposting.
- Tidak ada harga paket, alokasi fitur, kontrak gateway, ataupun token mentah yang disediakan oleh desain ini.

## Platform, paket, dan identitas

| Tabel | Tujuan | Kunci aturan |
|---|---|---|
| `business_types` | Katalog jenis usaha platform. | Rilis ini hanya mengaktifkan `LAUNDRY`; tidak ada tabel operasional bisnis lain. |
| `plans` | Identitas paket platform Freetier/Premium/Pro yang dipakai lintas jenis bisnis. | Nama paket bukan harga, hak fitur, atau penanda khusus Laundry. |
| `plan_versions` | Versi paket yang dapat dipublikasikan. | Langganan menyimpan versi, agar perubahan masa depan tidak mengubah kontrak lama. |
| `features` | Katalog kemampuan platform. | Tidak mengasumsikan feature mana masuk paket mana atau jenis bisnis mana yang dapat memakainya. |
| `plan_features` | Hak/limit per versi paket. | Satu feature per versi paket. |
| `plan_prices` | Harga per versi, mata uang, dan periode. | Tidak ada seed harga; nilai harus positif dan relasi eksplisit ke `plan_versions`. |
| `tenants` | Merchant dan profil situs bermerek. | `DRAFT → PENDING_SUBSCRIPTION → PENDING_PROFILE → ACTIVE`; provisioning dideduplikasi per pembuat + key dan fingerprint. |
| `staff_users` | Identitas login global, termasuk akun sebelum merchant dibuat. | Password hash dan status akun; role merchant tetap berada di membership tenant. |
| `tenant_memberships` | Keanggotaan user pada merchant. | Role tepat `OWNERMERCHANT` atau `CASHIER`; satu user sekali per tenant. |
| `outlets` | Lokasi operasional merchant. | Selalu milik tenant, termasuk zona waktu. |
| `membership_outlet_access` | Otorisasi outlet untuk membership. | Harus dipakai guard aplikasi, bukan client request. |

`SUPERADMIN` tetap konsep platform yang terisolasi dari tabel membership tenant dan runtime autentikasi saat ini tidak diubah oleh dokumen ini.

## Langganan dan pembayaran platform

| Tabel | Tujuan | Kunci aturan |
|---|---|---|
| `subscriptions` | Masa berlangganan merchant pada versi/harga paket. | Partial unique index membatasi satu subscription hidup per tenant. |
| `platform_invoices` | Tagihan SaaS kepada merchant. | Terpisah total dari invoice pelanggan laundry; nilai positif, tanpa alur gratis implisit. |
| `platform_payment_attempts` | Percobaan bayar ke gateway platform. | Idempotency tenant dan referensi provider unik. |
| `gateway_events` | Inbox webhook gateway yang dideduplikasi. | Unique provider/account/event; simpan digest payload, bukan rahasia. |

Alur aktivasi: tenant/profil dapat dibuat `DRAFT`, subscription dibuat `PENDING_PAYMENT`, event payment terverifikasi mengaktifkan subscription, lalu profil lengkap mengaktifkan tenant. Retry onboarding dengan key yang sama harus memakai fingerprint identik; fingerprint berbeda ditolak. Detail provider dan kebijakan masa aktif tetap keputusan produk/integrasi.

## Pelanggan dan katalog Laundry

| Tabel | Tujuan | Kunci aturan |
|---|---|---|
| `customers` | Pelanggan milik merchant. | Tidak pernah bersifat global lintas tenant. |
| `customer_addresses` | Alamat tersimpan pelanggan. | Maksimal satu alamat default per pelanggan/tenant. |
| `laundry_service_categories` | Pengelompokan layanan per outlet. | Katalog Laundry saja. |
| `laundry_services` | Layanan bertarif `KG` atau `PIECE`. | Menyimpan minimum dan increment, tanpa memaksakan aturan pembulatan. |
| `outlet_laundry_service_prices` | Tarif efektif layanan per outlet. | Order menyimpan snapshot; perubahan tarif tidak memengaruhi riwayat. |

Untuk `PIECE`, CHECK juga menolak kuantitas pecahan. Aturan awal `EXACT_WITH_MINIMUM` menggunakan maksimum antara jumlah aktual dan minimum tagihan; tidak otomatis membulatkan naik. Increment menyatakan ketelitian input yang divalidasi aplikasi. Aturan pembulatan tambahan memerlukan keputusan produk dan perluasan CHECK sebelum diaktifkan. Interval harga tidak boleh tumpang tindih untuk layanan/mata uang yang sama; service memvalidasinya di bawah lock saat mempublikasikan harga.

## Pesanan dan fulfilment

| Tabel | Tujuan | Kunci aturan |
|---|---|---|
| `orders` | Identitas transaksi bersama, dibatasi Laundry pada V1. | Nomor order per outlet; idempotency key + fingerprint mendeteksi replay/payload berbeda. |
| `laundry_orders` | Detail khusus penerimaan laundry dan estimasi siap. | Shared PK membatasi maksimal satu detail per order; service wajib membuat detail. Berat/jumlah aktual berada pada item. |
| `laundry_order_items` | Baris layanan/pakaian. | Billed/actual quantity, minimum/increment, harga/sumber harga, duration dan janji siap adalah snapshot. `PIECE` wajib integral. |
| `laundry_order_status_history` | Riwayat append-only perubahan status. | Mencatat aktor, waktu, alasan. |
| `pickup_delivery_jobs` | Pickup sebelum order dan delivery sesudah proses. | Pickup boleh tanpa order sebelum diterima; delivery dan job selesai wajib punya order. |

`orders` sengaja sangat kecil sebagai identitas/transaksi bersama dan pada V1 dibatasi oleh invariant aplikasi: seluruh order adalah Laundry. Tidak ada tabel operasional untuk tipe bisnis lain. Jika bisnis kedua benar-benar diimplementasikan kelak, tambahkan discriminator bisnis yang tervalidasi beserta detail PK/FK-nya melalui keputusan skema terpisah; jangan menganggap kolom `source` sebagai discriminator bisnis.

Status order yang tersedia: `RECEIVED`, `WEIGHED`, `PROCESSING`, `READY`, `HANDED_OVER`, `COMPLETED`, `CANCELLED`. Aturan transisi aplikasi: `RECEIVED → WEIGHED → PROCESSING → READY → HANDED_OVER`; pembayaran invoice dapat dicatat setelah `READY`, tetapi order baru menjadi `COMPLETED` ketika payment penuh dan handover sudah keduanya ada. Cancellation hanya sebelum `READY`, dengan alasan dan audit. Ini asumsi V1 yang perlu keputusan produk bila pembatalan sesudah invoice diperlukan. Invoice pelanggan wajib diterbitkan atomik saat transisi ke `READY`, bukan pada `HANDED_OVER` atau saat pembayaran. Status final bukan pengganti riwayat.

`CHECK` hanya memvalidasi nilai satu baris (misalnya alasan pembatalan). Ia tidak dapat membuktikan urutan transisi, total header = total baris, invoice tepat saat READY, atau order delivery sesuai status; semua itu invariant transaksi layanan dengan row locking/optimistic locking.

## Invoice dan pembayaran pelanggan

| Tabel | Tujuan | Kunci aturan |
|---|---|---|
| `customer_invoices` | Tagihan pelanggan untuk satu order Laundry. | Snapshot subtotal/diskon/fee/pajak/total; total positif sehingga tidak ada dead-end invoice nol. |
| `customer_invoice_lines` | Snapshot item pada invoice. | Menaut ke baris order dan mengunci satu item sekali dalam invoice. |
| `customer_payments` | Pencatatan manual pembayaran pelanggan. | Menyimpan tendered/change/mata uang/fingerprint; satu payment `POSTED` per invoice. |

MVP menerima pembayaran penuh manual setelah invoice `ISSUED` dan order `READY` atau `HANDED_OVER`. Layanan dalam satu transaksi harus memverifikasi nominal = `customer_invoices.total_amount` dan mengubah invoice menjadi `PAID`; order menjadi `COMPLETED` hanya jika handover juga sudah ada. Tidak ada deposit, cicilan, split payment, refund otomatis, atau pembatalan setelah paid. Partial unique index adalah pagar terakhir terhadap dua payment posted; verifikasi jumlah dan lifecycle tetap atomik di aplikasi.

## Inventori, audit, dan outbox

| Tabel | Tujuan | Kunci aturan |
|---|---|---|
| `inventory_items` | Persediaan sederhana (detergen, plastik, dll.). | Bukan resep, supplier, atau procurement. |
| `inventory_balances` | Saldo mutakhir per item. | Dikunci/di-version saat mutasi; tidak boleh negatif di V1. |
| `inventory_movements` | Ledger append-only receipt/usage/adjustment/reversal. | Key/fingerprint idempoten; reversal wajib menunjuk mutasi asal. |
| `audit_logs` | Jejak tindakan penting. | Tenant/outlet/aktor terikat FK bila tersedia; entity target sengaja generic dan didokumentasikan. |
| `outbox_messages` | Event setelah commit (mis. invoice READY). | Payload internal; channel pengiriman belum dipilih. |

`audit_logs.entity_type/entity_id` dan `outbox_messages.aggregate_type/aggregate_id` adalah satu-satunya referensi generik yang disengaja: keduanya adalah log/event append-only, bukan relasi operasional atau dasar otorisasi. Audit merchant selalu wajib tenant. Outbox memiliki `deduplication_key` unik per tenant, misalnya `invoice:<id>:issued` atau `order:<id>:version:<n>`, sehingga retry tidak menduplikasi peristiwa tetapi perubahan status berikutnya tetap dapat diterbitkan. Penerbit outbox hanya boleh berjalan setelah commit; polling tetap fallback.

Aturan service yang belum berupa trigger database: jumlah dan mata uang payment harus sama dengan invoice; jumlah baris invoice harus cocok dengan subtotal; harga snapshot harus berasal dari penawaran yang dipilih; actual quantity harus lengkap sebelum invoice; satu reversal harus meniadakan nilai mutasi asal dan bukan membalik mutasi reversal. Status VOID disediakan untuk prosedur koreksi yang diaudit di masa depan, bukan izin menghapus atau membatalkan pelunasan pada rilis pertama. Foreign key aktor membatasi tenant, sedangkan status membership dan izin outlet tetap diperiksa ketika tindakan dijalankan.

## Indeks dan review statis

Indeks mendukung antrean outlet, pencarian pesanan pelanggan, riwayat, job pickup/delivery, invoice, ledger inventori, audit, dan outbox belum terkirim. Foreign key komposit dipakai untuk relasi tenant/outlet, order, customer, service, membership aktor, dan invoice. Tidak ada `DROP`, `ALTER`, `IF NOT EXISTS`, seed data, koneksi database, maupun migrasi pada desain ini.

Asumsi yang masih memerlukan keputusan produk sebelum implementasi: gateway/platform provider dan event contract, harga/entitlement tiap paket, aturan pembulatan berat, status penagihan saat delivery gagal, penugasan kurir, serta apakah invoice publik memang diperlukan. Skema tidak memilih nilai-nilai tersebut.
