# Organisasi Entity

Status: rancangan entity telah ditambahkan, tetapi migration Flyway dan use case/service belum diimplementasikan. Dibuat 16 September 2026.

Package `entity` tidak lagi menjadi folder datar. Struktur ini membedakan data yang digunakan seluruh merchant dari data operasional Laundry dan F&B.

| Package | Isi | Dipakai oleh |
|---|---|---|
| `entity.shared.identity` | akun legacy kompatibel, user global, membership tenant, akses outlet | Semua bisnis |
| `entity.shared.merchant` | tenant/merchant dan outlet | Semua bisnis |
| `entity.shared.billing` | jenis bisnis, paket, versi/harga/fitur paket, subscription, tagihan dan payment gateway platform | Platform SaaS |
| `entity.shared.customer` | pelanggan dan alamat | Laundry sekarang; bisnis lain nanti bila relevan |
| `entity.shared.sales` | order umum, invoice pelanggan, baris invoice, pembayaran pelanggan | Transaksi merchant |
| `entity.shared.inventory` | item, saldo, dan mutasi persediaan | Laundry sekarang; F&B dapat memakai kemudian |
| `entity.shared.audit` | audit log serta outbox event setelah commit | Semua bisnis |
| `entity.laundry` | layanan/tarif, order dan item laundry, riwayat proses, pickup/delivery | Produk pertama: Laundry |
| `entity.fnb` | kategori, produk, opsi, meja/QR, order, item, pembayaran, diskon, riwayat status F&B | Coffee shop/F&B, tahap berikutnya |

## Entity yang ditambahkan untuk Laundry

`LaundryServiceCategoryEntity`, `LaundryServiceEntity`, `OutletLaundryServicePriceEntity`, `LaundryOrderEntity`, `LaundryOrderItemEntity`, `LaundryOrderStatusHistoryEntity`, dan `PickupDeliveryJobEntity` adalah entity yang sebelumnya belum ada.

`LaundryOrderItemEntity` menyimpan snapshot nama layanan, unit, harga, kuantitas aktual, kuantitas tertagih, minimum, aturan tarif, durasi, kondisi, dan total. Ini penting karena harga master atau aturan minimum dapat berubah setelah cucian diterima.

`LaundryOrderEntity` memakai shared primary key dari `OrderEntity`. Satu order Laundry memiliki paling banyak satu detail Laundry; service wajib membuat keduanya secara atomik ketika order diterima.

## Entity yang ditambahkan untuk fondasi SaaS

`StaffUserEntity` dan `TenantMembershipEntity` memisahkan identitas akun global dari role merchant. `BusinessTypeEntity`, `PlanEntity`, `PlanVersionEntity`, `FeatureEntity`, `PlanFeatureEntity`, `PlanPriceEntity`, `SubscriptionEntity`, `PlatformInvoiceEntity`, `PlatformPaymentAttemptEntity`, dan `GatewayEventEntity` memisahkan langganan owner ke platform dari pembayaran pelanggan laundry.

`CustomerInvoiceEntity` dan `CustomerPaymentEntity` adalah tagihan pelanggan. Mereka tidak boleh digabung dengan `PlatformInvoiceEntity` dan `PlatformPaymentAttemptEntity`, karena penerima uang, status, dan auditnya berbeda.

## Perubahan F&B

Entity F&B lama dipindahkan tanpa mengubah nama tabel legacy: `CategoryEntity`, `ProductEntity`, `OptionGroupEntity`, `ProductOptionEntity`, `DiningTableEntity`, `TableQrTokenEntity`, `SalesOrderEntity`, `OrderItemEntity`, `OrderItemOptionEntity`, `PaymentEntity`, dan `DiscountEntity`. `FnbOrderStatusHistoryEntity` ditambahkan agar transisi dapur dapat diaudit ketika modul F&B diimplementasikan.

F&B tidak memakai entity Laundry. Sebaliknya, keduanya memakai tenant, outlet, identitas, pelanggan, audit, serta komponen yang benar-benar sama dari `shared`.

## Batas implementasi saat ini

Entity bukan fitur jadi. Database aktif masih memakai migration F&B lama dan `spring.jpa.hibernate.ddl-auto=update`. Jangan menjalankan aplikasi sebagai cara membuat skema target. Tahap berikutnya harus membuat migration Flyway yang aman, menonaktifkan pembentukan schema otomatis, lalu mengimplementasikan onboarding, order Laundry, invoice, pembayaran, dan otorisasi membership secara berurutan.
