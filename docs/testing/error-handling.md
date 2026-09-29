# Error handling: acceptance dan skenario verifikasi

Perubahan 26 September 2026: exception autentikasi, sesi, izin, dan konflik
onboarding dipisahkan. Response tetap memakai kontrak `ApiResponse` yang ada:
`success`, `code`, `message`, `timestamp`, serta `validationErrors` jika relevan.
Tidak ada field error code baru atau perubahan schema database.

## Pemetaan

| Penyebab | HTTP | Respons |
| --- | ---: | --- |
| Login gagal | 401 | Pesan kredensial umum; tidak membedakan email, password, atau status akun |
| Akun principal tidak ditemukan | 401 | Autentikasi tidak lagi valid; masuk kembali |
| Akun belum menyelesaikan profil saat memilih merchant/outlet | 409 | Selesaikan profil akun |
| Akun dinonaktifkan setelah autentikasi | 403 | Akun tidak diizinkan |
| Membership/outlet/grant tidak ditemukan saat memilih merchant | 404 | Pesan sama untuk data tidak ada dan tidak dapat diakses |
| Usaha/langganan tidak aktif setelah akses outlet terverifikasi | 409 | Pesan status usaha/langganan |
| Tahap/intent onboarding Freetier tidak sesuai | 409 | Pesan sesuai tahap atau jalur pemilik usaha |
| Refresh token/sesi tidak berlaku | 401 | Pesan sesi tidak berlaku; masuk kembali |
| Bearer token hilang atau invalid | 401 | `ApiResponse` dari adapter security; challenge Bearer dipertahankan |
| Format Bearer request ditolak sebagai malformed oleh Spring Security | 400 | Pesan format header Authorization; status protokol dipertahankan |
| Role/scope/CSRF ditolak filter | 403 | Pesan izin atau CSRF; `ApiResponse` |
| JSON/body tidak dapat dibaca | 400 | Pesan JSON/body dan tipe data |
| Header/parameter wajib hilang | 400 | Nama header/parameter yang wajib |
| Parameter salah tipe atau melanggar constraint | 400 | Pesan validasi parameter |
| Metode HTTP tidak didukung | 405 | Pesan metode dan header Allow |
| Content-Type tidak didukung | 415 | Pesan Content-Type |
| Accept tidak didukung | 406 | Pesan format respons; representasi JSON tetap bergantung content negotiation |
| Endpoint/static resource tidak ditemukan | 404 | Pesan endpoint tidak ditemukan |
| Error internal tak terduga | 500 | Pesan umum; detail tidak dikirim ke client |

`GlobalExceptionHandler` tetap memiliki handler per kategori dengan
`@ResponseStatus`; pesan berasal dari exception atau katalog `ErrorMessage`.
Exception aplikasi menerima pesan kontekstual pada constructor. String pesan
publik harus disediakan server dan tidak berisi token, password, SQL, atau
identitas resource tenant lain.

`ApiSecurityExceptionHandler` adalah adapter filter, bukan handler HTTP khusus
fitur. Adapter memakai serializer aplikasi dan mempertahankan status/challenge
dari Bearer handler Spring Security. Error resource server juga dikonfigurasi
langsung pada `oauth2ResourceServer`, selain `exceptionHandling`.

## Transaksi dan tenant boundary

- Refresh memakai `noRollbackFor = SessionInvalidException.class`; pencabutan
  sesi karena refresh gagal atau reuse harus tetap commit.
- Error lain tetap rollback secara normal. `OperationConflictException` untuk
  langganan pada pemilihan merchant tidak dikecualikan dari rollback.
- Membership dan grant outlet diverifikasi sebelum pesan status merchant atau
  langganan diberikan. Lookup tetap memakai user/tenant/outlet yang sesuai.
- Invitation invalid, kedaluwarsa, salah pemilik, atau tidak dapat dipakai
  memberikan pesan 404 yang sama.
- Retry onboarding memeriksa akun sebelum mengembalikan hasil idempotent;
  akun dinonaktifkan tidak mendapatkan hasil retry.

## Skenario yang direkomendasikan (belum dijalankan)

### Unit

- Login tetap memberikan pesan sama untuk email salah/password salah/status
  akun yang ditolak.
- Pemilihan merchant membedakan akun hilang (401), pending profile (409),
  disabled (403), resource tidak dapat diakses (404), dan subscription (409).
- Freetier menolak intent/tahap yang salah dengan 409; retry setelah akun
  disabled menghasilkan 403.
- Refresh gagal menggunakan `SessionInvalidException`; jangan mengganti
  hasil refresh dengan pesan password.

### Component

- MVC: HTTP status sama dengan `code`, `success=false`, pesan kontekstual,
  timestamp tersedia, dan field null tidak muncul.
- JSON rusak, UUID invalid, header Idempotency-Key hilang, dan parameter wajib
  hilang menghasilkan 400, bukan catch-all 500.
- Metode HTTP invalid menghasilkan 405 dan Allow; Content-Type invalid 415.
- Filter: token hilang/invalid, role/scope salah, dan CSRF invalid memakai
  envelope JSON; WWW-Authenticate tetap tersedia pada kegagalan Bearer.
- Error internal memberikan 500 tanpa SQL, token, atau pesan exception mentah.

### Integration

- Gunakan database test terisolasi untuk memastikan sesi revoked tetap
  tersimpan setelah refresh invalid/reuse, sedangkan error persistence tetap
  rollback.
- Verifikasi isolasi tenant: resource tenant lain dan ID tidak ada memberikan
  status/pesan yang sama, tanpa membocorkan status subscription/merchant.
- Jalankan alur register, choose intent, start Freetier, complete profile,
  select merchant, refresh, dan logout untuk memastikan jalur berhasil tetap
  bekerja.

Status: READY FOR TESTING. Compile boleh dilakukan tanpa mengeksekusi test;
unit/component/integration dijalankan hanya atas permintaan pengguna.
