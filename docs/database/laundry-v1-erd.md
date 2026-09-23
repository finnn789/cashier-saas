# ERD Laundry v1

Diagram ini memperlihatkan relasi utama dari [SQL referensi](laundry-v1.sql). Setiap `tenants` adalah merchant. Relasi composite tenant/outlet, aktor, dan kolom audit tidak semuanya digambar agar diagram tetap terbaca; constraint lengkap ada di SQL.

## 1. Identitas dan merchant

```mermaid
erDiagram
    business_types ||--o{ tenants : jenis_bisnis
    staff_users ||--o{ tenant_memberships : bergabung
    tenants ||--o{ tenant_memberships : memiliki
    tenants ||--o{ outlets : memiliki
    tenant_memberships ||--o{ membership_outlet_access : diberi_akses
    outlets ||--o{ membership_outlet_access : membatasi
```

Satu user boleh memiliki beberapa membership. Satu membership memiliki satu role Owner (`OWNERMERCHANT`) atau Cashier (`CASHIER`). Minimal satu owner aktif per tenant merupakan invariant service, bukan konsekuensi dari diagram FK. Tenant provisional dapat belum memiliki outlet/profil lengkap.

## 2. Penawaran paket dan langganan

```mermaid
erDiagram
    plans ||--o{ plan_versions : dipublikasikan
    plan_versions ||--o{ plan_prices : harga_per_periode
    plan_versions ||--o{ plan_features : hak_fitur
    features ||--o{ plan_features : tersedia
    tenants ||--o{ subscriptions : berlangganan
    plan_prices ||--o{ subscriptions : dipilih
```

Riwayat langganan dapat lebih dari satu, tetapi subscription current dibatasi sesuai constraint dan kebijakan billing. Paket/fitur versi yang sudah dipublikasikan tidak diubah untuk transaksi historis.

## 3. Owner membayar platform

```mermaid
erDiagram
    subscriptions ||--o{ platform_invoices : ditagihkan
    platform_invoices ||--o{ platform_payment_attempts : dicoba_bayar
    platform_payment_attempts o|--o{ gateway_events : direkonsiliasi
```

Gateway dapat mengirim event duplikat atau datang sebelum referensi attempt berhasil dicocokkan. Identitas provider/account/event menjaga deduplikasi, sedangkan verifikasi dan rekonsiliasi tetap dilakukan server. Banyak attempt bukan izin mengaktifkan langganan berkali-kali.

## 4. Pelanggan, layanan, dan pesanan laundry

```mermaid
erDiagram
    tenants ||--o{ customers : melayani
    customers ||--o{ customer_addresses : memiliki
    tenants ||--o{ laundry_service_categories : mengelompokkan
    laundry_service_categories o|--o{ laundry_services : kategori
    laundry_services ||--o{ outlet_laundry_service_prices : ditawarkan
    outlets ||--o{ outlet_laundry_service_prices : menetapkan_tarif
    customers o|--o{ orders : memesan
    outlets ||--o{ orders : menerima
    orders ||--o| laundry_orders : detail_laundry
    laundry_orders ||--o{ laundry_order_items : rincian_layanan
    outlet_laundry_service_prices ||--o{ laundry_order_items : tarif_sumber
    laundry_orders ||--o{ laundry_order_status_history : perubahan_proses
```

Pada alur penerimaan yang berhasil, header order harus mempunyai satu detail laundry dan setidaknya satu item. FK hanya menjamin child menunjuk parent yang valid; service membuat semuanya dalam satu transaksi. Tarif dan jumlah yang digunakan disalin ke item. Perubahan master harga tidak mengubah order lama.

## 5. Tagihan pelanggan dan penyerahan

```mermaid
erDiagram
    orders ||--o| customer_invoices : tagihan_final
    customer_invoices ||--o{ customer_invoice_lines : snapshot_rincian
    customer_invoices ||--o{ customer_payments : pembayaran
    customers ||--o{ pickup_delivery_jobs : meminta
    orders o|--o{ pickup_delivery_jobs : terkait
```

PICKUP boleh belum terhubung ke order. DELIVERY harus terhubung ke order yang sudah siap. Rilis pertama menerima satu pembayaran penuh yang berhasil per invoice; relasi fisik bukan aktivasi fitur cicilan. Invoice dan outbox tagihan siap dibuat atomik ketika order READY, tanpa menunggu penyerahan. Outbox mereferensikan agregat secara logis melalui event; tautannya bukan FK invoice sehingga tidak digambar sebagai relasi fisik.

## 6. Persediaan

```mermaid
erDiagram
    tenants ||--o{ inventory_items : memiliki
    inventory_items ||--o| inventory_balances : saldo_item_outlet
    outlets ||--o{ inventory_balances : menyimpan
    inventory_items ||--o{ inventory_movements : ledger
    outlets ||--o{ inventory_movements : lokasi_mutasi
    orders o|--o{ inventory_movements : pemakaian_terkait
```

Katalog item persediaan rilis pertama dimiliki per outlet; satu item tersebut memiliki maksimal satu baris saldo. Ledger merupakan sumber histori. Saldo diperbarui bersama mutasi di bawah lock dan dapat direkonsiliasi terhadap jumlah mutasi. Tidak ada foreign key umum `source_type/source_id` untuk hubungan order; tautannya konkret. `audit_logs` merekam tindakan lintas modul dan sengaja tidak digambar ke setiap tabel.

## 7. Peristiwa utama operasional

```mermaid
flowchart TD
    A[Terima dan timbang cucian] --> B[Catat order dan snapshot tarif]
    B --> C[Proses sesuai layanan]
    C --> D[Semua pekerjaan READY]
    D --> E[Terbitkan invoice dan outbox secara atomik]
    E --> F[Tampilkan atau kirim tagihan]
    D --> G[Ambil di outlet atau antar]
    G --> H[Catat HANDED_OVER]
    F --> I[Catat pembayaran penuh]
    H --> J{Sudah diserahkan dan lunas?}
    I --> J
    J -->|Ya| K[Selesai keseluruhan]
    J -->|Belum| L[Tetap terlihat sebagai pekerjaan atau tagihan terbuka]
```

Diagram alur adalah kontrak use case yang akan diimplementasikan. SQL referensi belum mengirim notifikasi, menjalankan gateway, atau memvalidasi seluruh transisi lintas tabel.
