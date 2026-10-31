# TASK-048: Frontend Specification — Silent Refresh & Last Outlet Persistence (UX Optimization)

## Deskripsi Task
Dokumen ini adalah spesifikasi teknis lengkap yang siap diserahkan langsung ke AI Agent Frontend untuk mengimplementasikan optimasi User Experience (UX) pada alur autentikasi dan pemilihan merchant.

Tujuan utama:
1. **User tidak perlu login setiap kali membuka web**: Sesi login bertahan 7 hari menggunakan cookie refresh token yang aman (`HttpOnly`). Saat web dibuka, FE otomatis menjalankan *Silent Refresh*.
2. **User tidak perlu memilih merchant berulang kali**: Begitu toko/outlet pernah dipilih sekali, sistem mengingatnya. Pada login berikutnya, FE otomatis membuka toko terakhir tersebut (*Silent Auto-Select*).

---

## Spesifikasi Kontrak API Backend

Base URL Backend: `http://localhost:8080` (sesuaikan environment).  
Semua request wajib mengaktifkan `withCredentials: true` (CORS credentials) agar cookie `cashier_refresh` dan `XSRF-TOKEN` dapat terbaca/terkirim.

### 1. CSRF Token Endpoint
- **URL**: `GET /api/v1/auth/csrf`
- **Auth**: Public (Tanpa token)
- **Response** (HTTP 200):
  ```json
  {
    "status": "SUCCESS",
    "message": "CSRF token tersedia.",
    "data": "100a9446-5ef1-4b77-8fa7-9a997d9179fc"
  }
  ```
- **Catatan**: Token CSRF juga otomatis dikirim backend dalam cookie `XSRF-TOKEN` (bisa dibaca via document.cookie jika same-origin/subdomain).

### 2. Silent Refresh Endpoint
- **URL**: `POST /api/v1/auth/refresh`
- **Auth**: Public, tetapi **WAJIB** menyertakan:
  - Header: `X-XSRF-TOKEN: <csrf_token_value>`
  - Cookie otomatis: `cashier_refresh` (disertakan oleh browser via `withCredentials: true`)
- **Response jika Sesi Sudah Pernah Memilih Toko** (HTTP 200):
  ```json
  {
    "status": "SUCCESS",
    "message": "Sesi diperbarui.",
    "data": {
      "accessToken": "eyJhbGciOi...",
      "tokenType": "Bearer",
      "expiresAt": "2026-10-24T15:30:00Z",
      "role": "OWNERMERCHANT", // atau "CASHIER"
      "tenantId": "e1f1a1a1-...",
      "outletId": "d2f2b2b2-...",
      "nextStep": "OPERATE"
    }
  }
  ```
- **Response jika Sesi Belum Memilih Toko** (HTTP 200):
  ```json
  {
    "status": "SUCCESS",
    "data": {
      "accessToken": "eyJhbGciOi...",
      "tokenType": "Bearer",
      "expiresAt": "2026-10-24T15:30:00Z",
      "nextStep": "SELECT_MERCHANT_OUTLET"
    }
  }
  ```
- **Response jika Sesi Kedaluwarsa / Tidak Ada Cookie** (HTTP 401):
  ```json
  {
    "status": "UNAUTHORIZED",
    "message": "Sesi tidak valid atau telah berakhir."
  }
  ```

### 3. Password Login Endpoint
- **URL**: `POST /api/v1/auth/login`
- **Body**:
  ```json
  {
    "email": "user@example.com",
    "password": "Password123!"
  }
  ```
- **Response** (HTTP 200):
  ```json
  {
    "status": "SUCCESS",
    "message": "Login berhasil.",
    "data": {
      "accessToken": "eyJhbGciOi...",
      "tokenType": "Bearer",
      "expiresAt": "2026-10-24T15::00Z",
      "nextStep": "SELECT_MERCHANT_OUTLET"
    }
  }
  ```
- *Keterangan*: Backend otomatis menetapkan cookie `cashier_refresh` (Max-Age 7 hari) via response header `Set-Cookie`.

### 4. List Accessible Outlets Endpoint
- **URL**: `GET /api/v1/auth/outlets`
- **Auth**: Header `Authorization: Bearer <accessToken>` (scope: ACCOUNT)
- **Response** (HTTP 200):
  ```json
  {
    "status": "SUCCESS",
    "data": [
      {
        "tenantId": "e1f1a1a1-...",
        "tenantName": "Kopi Mantap",
        "outletId": "d2f2b2b2-...",
        "outletName": "Cabang Sudirman",
        "role": "OWNERMERCHANT"
      }
    ]
  }
  ```

### 5. Select Merchant Endpoint
- **URL**: `POST /api/v1/auth/select-merchant`
- **Auth**: Header `Authorization: Bearer <accessToken>` (scope: ACCOUNT)
- **Body**:
  ```json
  {
    "tenantId": "e1f1a1a1-...",
    "outletId": "d2f2b2b2-..."
  }
  ```
- **Response** (HTTP 200):
  ```json
  {
    "status": "SUCCESS",
    "message": "Merchant dan outlet dipilih.",
    "data": {
      "accessToken": "eyJhbGciOi... (Scope: MERCHANT)",
      "tokenType": "Bearer",
      "expiresAt": "2026-10-24T15:30:00Z",
      "role": "OWNERMERCHANT",
      "tenantId": "e1f1a1a1-...",
      "outletId": "d2f2b2b2-...",
      "nextStep": "OPERATE"
    }
  }
  ```

### 6. Logout Endpoint
- **URL**: `POST /api/v1/auth/logout`
- **Auth**: Header `X-XSRF-TOKEN: <csrf_token_value>` dan `withCredentials: true`.
- **Response** (HTTP 200): Backend mencabut sesi di DB dan membersihkan cookie `cashier_refresh`.

---

## Kriteria Penerimaan (Acceptance Criteria)

### AC-1: Silent Refresh Saat Aplikasi Dibuka (App Boot)
- Ketika web pertama kali dimuat / di-refresh:
  1. FE menampilkan SplashScreen / Loading spinner.
  2. FE mengambil CSRF token (dari cookie `XSRF-TOKEN` atau via `GET /api/v1/auth/csrf`).
  3. FE memanggil `POST /api/v1/auth/refresh`.
  4. **Kasus Sukses (Sesi Valid & Sudah Ada Toko Terpilih)**:
     - Jika `nextStep === "OPERATE"`, simpan `accessToken` ke memory state dan langsung arahkan user ke halaman utama/POS (`/pos` atau `/dashboard`).
  5. **Kasus Sesi Valid tapi Belum Ada Toko Terpilih**:
     - Jika `nextStep === "SELECT_MERCHANT_OUTLET"`, periksa `localStorage` (lihat AC-3).
  6. **Kasus Gagal (Sesi Kedaluwarsa / Belum Login)**:
     - Jika mendapat status HTTP 401, bersihkan memory token dan arahkan user ke halaman `/login`.

### AC-2: Penyimpanan Outlet Terakhir (Last Outlet Persistence)
- Kunci `localStorage`: `cashier_last_selection`
- Format JSON:
  ```json
  {
    "tenantId": "uuid-string",
    "outletId": "uuid-string"
  }
  ```
- Setiap kali user berhasil memilih toko (melalui `/api/v1/auth/select-merchant`), FE wajib memperbarui data di `localStorage` ini.

### AC-3: Silent Auto-Select Saat Login dengan Password
- Ketika user melakukan login via `POST /api/v1/auth/login` dan menerima `nextStep === "SELECT_MERCHANT_OUTLET"`:
  1. FE memeriksa apakah ada data di `localStorage.getItem('cashier_last_selection')`.
  2. **Jika ADA**:
     - Jangan langsung tampilkan modal/halaman pilihan toko.
     - Tampilkan loading state *"Membuka outlet terakhir..."*.
     - Segera tembak `POST /api/v1/auth/select-merchant` menggunakan `tenantId` dan `outletId` dari `localStorage` dengan token akun yang baru didapat.
     - **Jika Sukses**: Simpan token operasional baru (`scope: MERCHANT`) dan langsung arahkan ke Dashboard/POS (`/pos`).
     - **Jika Gagal** (misal 404 outlet sudah dihapus atau subscription habis): Hapus data di `localStorage` dan buka modal pilihan outlet manual.
  3. **Jika TIDAK ADA**:
     - Tampilkan halaman/modal daftar outlet dari `GET /api/v1/auth/outlets`.
     - Saat user mengklik salah satu outlet, panggil `POST /api/v1/auth/select-merchant` dan simpan pilihannya ke `localStorage`.

### AC-4: Axios/Fetch Interceptor untuk Auto Refresh (Anti-Race Condition)
- Ketika access token kedaluwarsa saat transaksi (HTTP 401 pada request API operasional):
  1. Interceptor mencegat error 401.
  2. Menggunakan mekanisme **Queue / Mutex Flag** (`isRefreshing`): jika ada 3 request API yang 401 bersamaan, hanya **1 request** yang memanggil `POST /api/v1/auth/refresh`. Request lainnya menunggu antrean.
  3. *Penting*: Backend menerapkan *Anti-Token Reuse Detection*. Jangan pernah mengirim request refresh paralel dengan token yang sama karena sesi akan langsung dicabut!
  4. Setelah refresh berhasil, update access token baru dan ulangi (*retry*) semua request yang tertunda.

### AC-5: Logout yang Bersih
- Saat user menekan tombol "Logout":
  1. Ambil CSRF token.
  2. Panggil `POST /api/v1/auth/logout` dengan header `X-XSRF-TOKEN`.
  3. Hapus in-memory access token.
  4. Catatan: Data `localStorage` (`cashier_last_selection`) **boleh tetap dipertahankan** agar jika user yang sama login kembali di perangkat yang sama, toko terakhirnya tetap diingat.
  5. Arahkan user ke halaman `/login`.

---

## Panduan Keamanan (Security Guardrails)
- ❌ **DILARANG** menyimpan `accessToken` di `localStorage` atau `sessionStorage` (rentan terhadap serangan XSS). `accessToken` wajib disimpan di memory state (React State, Zustand, Vue Pinia, dsb).
- ❌ **JANGAN** mencoba membaca atau menulis cookie `cashier_refresh` dari JavaScript, karena cookie tersebut berstatus `HttpOnly` dan sepenuhnya dikelola browser.
- ✔️ `tenantId` dan `outletId` aman disimpan di `localStorage` karena hanya merupakan identifier publik (UUID), bukan kredensial rahasia.
- ✔️ Pastikan konfigurasi Axios/Fetch selalu memiliki `withCredentials: true`.
