# Struktur dan audit test

Audit statis: 26 September 2026. Audit dilakukan dengan membaca seluruh class
test, konfigurasi Maven, resource test, dan konfigurasi security yang terkait.
Test tidak dijalankan. Pemisahan mempertahankan metode test, assertion, mock,
dan perubahan test yang sudah ada di working tree.

## Batas setiap jenis test

| Jenis | Batas pengujian | Dependency |
| --- | --- | --- |
| Unit | Satu class/use case secara terisolasi; tidak memuat Spring context | Collaborator dimock; entity/value object nyata boleh dipakai |
| Component | Satu komponen/subsistem dengan beberapa class produksi nyata; Spring slice boleh dipakai | Dependency di luar batas komponen diganti test double; tidak memuat aplikasi penuh |
| Integration | Wiring aplikasi penuh atau integrasi persistence/infrastruktur nyata | Full Spring context atau adapter yang terhubung dependency nyata |

Definisi ini adalah konvensi repository. Jenis test ditentukan oleh batas yang
diuji, bukan package class produksi, panjang metode, atau nama file semata.

## Inventaris hasil audit

Semua path pada tabel relatif terhadap
`src/test/java/org/lite/project/cashiersaas/`.

| Class | Metode `@Test` | Jenis | Bukti dan lokasi baru |
| --- | ---: | --- | --- |
| `JwtTokenServiceTest` | 3 | Unit | `JwtEncoder` dimock, service dibuat langsung; `unit/component/` |
| `TenantJwtAuthenticationConverterTest` | 5 | Unit | Converter dibuat langsung, JWT sintetis, tanpa Spring; `unit/component/` |
| `MerchantAccessGuardTest` | 5 | Unit | Repository dimock dengan Mockito; `unit/service/` |
| `FreeTierOnboardingServiceTest` | 3 | Unit | Service dengan dependency mock; `unit/service/` |
| `CashierSaleServiceTest` | 6 | Unit | Repository dimock, perhitungan/snapshot menggunakan entity nyata; `unit/service/` |
| `SecurityAuthorizationIntegrationTest` | 4 | Integration | `@SpringBootTest`, full web context, security filter dan penerbit JWT nyata; `integration/security/` |
| `CashierSaasApplicationTests` | 1 | Integration | `@SpringBootTest` dan pemeriksaan startup context; `integration/application/` |

Total: **7 class / 27 metode**. Unit: **5 class / 22 metode**.
Integration: **2 class / 5 metode**. Component: **0 class / 0 metode**.
Folder `component/` disiapkan dengan README; tidak ada test yang dipaksakan
masuk kategori component.

## Temuan dan batas coverage

1. Struktur lama mencampur package produksi dan jenis test (`component/unit`,
   `service/unit`, `configuration/api`, serta test context di root).
   Struktur baru menempatkan jenis test sebagai folder pertama.
2. Sebelumnya tidak ada tag atau selector suite pada POM. Sekarang tiap class
   mempunyai tepat satu `@Tag`, dan Surefire memilih tag melalui profile Maven.
3. `SecurityAuthorizationIntegrationTest` memakai full application context
   meskipun assertion hanya memeriksa security pada URL `probe`. Respons 404
   membuktikan permintaan melewati security menuju endpoint yang tidak ada;
   ini belum memverifikasi controller bisnis atau payload respons.
4. `CashierSaasApplicationTests.contextLoads()` adalah startup smoke test;
   belum memverifikasi alur bisnis atau operasi database.
5. `src/test/resources/application.properties` hanya menetapkan properti JWT.
   Belum ada konfigurasi database test yang eksplisit, profile database khusus,
   atau Testcontainers pada test yang diaudit. Full context membawa konfigurasi
   JPA/Flyway; lingkungan database perlu disiapkan dan diisolasi sebelum suite
   integration dijalankan. Audit ini tidak memastikan konfigurasi runtime yang
   berasal dari environment.
6. Unit test checkout/onboarding membuktikan perhitungan serta interaksi dengan
   mock; belum membuktikan commit/rollback, constraint database, konkurensi
   idempotency, atau tenant isolation melalui query database nyata.
7. Belum ada component test untuk batas controller/validasi/JSON/exception
   handler, dan belum ada test persistence khusus dalam inventaris ini.

Temuan coverage dicatat sebagai tindak lanjut; pemisahan ini tidak menambah
skenario bisnis atau mengganti full-context security test menjadi Spring slice.

## Menjalankan suite

Gunakan Maven wrapper dari root repository. Jalankan test hanya setelah ada
permintaan eksplisit pengguna sesuai `AGENTS.md`.

| Suite | Perintah PowerShell |
| --- | --- |
| Unit (default) | `.\mvnw.cmd test` |
| Unit (eksplisit) | `.\mvnw.cmd test -Punit-tests` |
| Component | `.\mvnw.cmd test -Pcomponent-tests` |
| Integration | `.\mvnw.cmd test -Pintegration-tests` |
| Semua kategori | `.\mvnw.cmd test -Pall-tests` |

Perubahan perilaku: `test` dan `verify` tanpa profile hanya memilih tag `unit`.
Gunakan `all-tests` bila memerlukan semua kategori. Profile component belum
mempunyai skenario; tidak adanya test bukan bukti bahwa komponen sudah lolos.
Pilih satu profile suite per eksekusi. Semua suite memakai Surefire pada fase
`test`; integration belum memakai fase Failsafe tersendiri.

Filter memakai dukungan resmi
[JUnit tags pada Maven Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html#Filtering_by_Tags).

## Menambahkan test berikutnya

- Taruh class di `unit/<area>`, `component/<area>`, atau `integration/<area>`.
- Sesuaikan deklarasi package dengan lokasi file.
- Gunakan tepat satu tag class: `unit`, `component`, atau `integration`.
- Gunakan nama class berakhiran `Test` atau `Tests`; penamaan ini mempertahankan
  discovery Surefire yang dipakai repository sekarang.
- Test tanpa tag tidak masuk suite yang dikonfigurasi. Pastikan setiap class
  test baru memiliki tag ketika review.
- Sediakan dependency/database terisolasi untuk integration; jangan memakai
  fixture unit sebagai bukti bahwa persistence nyata sudah benar.
