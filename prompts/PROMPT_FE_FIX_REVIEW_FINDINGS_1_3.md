# Prompt terisi: perbaikan temuan review 1-3 untuk agent frontend KedaiMbo

Tempel seluruh isi blok di bawah ke agent frontend.

```
    PERAN
    Kamu agent PELAKSANA, bukan perancang. Kerjakan HANYA tugas di bawah, sekecil dan sesederhana
    mungkin, di repo E:\Java\cashier-saas-ui. Kamu tidak mewarisi percakapan apa pun: semua konteks ada
    di pesan ini dan di file yang kamu baca sendiri. Orkestrator yang memutuskan arsitektur; kamu
    menjalankan.
    
    RUANG LINGKUP (dikunci)
    - Boleh mengubah HANYA: src/api/fnbOrder.api.ts, src/api/inventory.api.ts, src/api/product.api.ts,
      src/api/client.ts, src/services/fnbRealtime.ts, src/pages/pos/PosPage.tsx,
      src/pages/products/components/RecordMovementModal.tsx, komponen di src/pages/products yang
      memicu bulk update status, src/types/inventory.ts (hanya bila perlu), tes yang menyentuh file itu,
      dan MAKSIMAL dua file baru kecil bila terbukti belum ada padanannya: satu hook cooldown
      (src/hooks/useRateLimitCooldown.ts) dan, hanya jika terjadi import melingkar,
      src/utils/idempotency.ts. Selain itu READ-ONLY.
    - Repo backend E:\Java\cashier-saas READ-ONLY (baca DTO/controller untuk kontrak; jangan ubah).
    - Jangan commit, push, init git, atau mengubah konfigurasi/secret/.env. Jangan menambah library.
    - Jika tugas menuntut sesuatu di luar lingkup (mis. endpoint backend atomik), BERHENTI di bagian itu
      dan laporkan sebagai rekomendasi. Jangan mengarang, jangan menambal diam-diam.
    
    URUTAN KERJA (wajib)
    1. BACA dulu: AGENTS.md/ARCHITECTURE.md/GEMINI.md di repo UI, lalu hanya file yang akan kamu ubah
       dan yang mereka panggil. Jangan menebak kontrak API.
    2. CARI sebelum menulis. Untuk setiap fungsi, tipe, hook, util, konstanta yang akan kamu buat: cari
       namanya DAN kata kuncinya di repo. Ada dan cukup = PAKAI. Hampir cukup = perluas secukupnya.
       Baru buat baru jika terbukti belum ada, dan catat hasil pencarianmu.
    3. SIMPAN baseline: repo UI BUKAN repo git. Sebelum mengedit, salin setiap file yang akan diubah ke
       E:\Java\cashier-saas-ui-baseline-20261111\ (di luar repo, struktur folder sama). Itu satu-satunya
       cara diff bisa diperiksa. Jangan menjalankan git init.
    4. UBAH seminimal mungkin. Satu langkah, satu alasan.
    5. VERIFIKASI, lalu LAPORKAN jujur.
    
    ATURAN KODE
    - DILARANG rewrite: jangan menulis ulang, memindahkan, merename, atau memformat ulang file yang
      sudah berfungsi. Jangan ubah gaya, struktur folder, nama ekspor publik, atau komponen tak
      terkait. Diff kecil dan terbaca. Jangan hapus kode yang masih dipakai.
    - DILARANG duplikat: satu tanggung jawab = satu tempat. Satu generator idempotency key, satu
      logika cooldown 429, satu konstanta untuk tiap angka. Logika error/retry hanya di lapisan pusat
      (client.ts interceptor, queryClient), bukan disalin ke halaman.
    - DILARANG tight coupling: komponen tidak memanggil HTTP/URL langsung (hook -> modul api). Tanpa
      import melingkar, tanpa state global baru di luar yang diminta, tanpa angka sihir.
    - Seringan mungkin: tanpa abstraksi/wrapper/HOC/opsi "untuk nanti". Dua opsi sama benar = pilih
      yang menambah kode paling sedikit.
    - Tanpa `any`, `@ts-ignore`, atau mematikan lint. Tanpa import tak terpakai.
    
    TUGAS: tiga perbaikan dari review. Kerjakan berurutan, laporkan per temuan.
    
    === TEMUAN 1: idempotency key harus dibuat SEKALI per aksi pengguna ===
    Masalah: key baru dibuat setiap mutationFn dipanggil, jadi retry atau klik ganda setelah timeout
    membuat order atau gerakan stok GANDA (backend hanya dedup bila key sama; key sama + payload
    berbeda = 409).
    Lokasi:
    - src/api/fnbOrder.api.ts useCreateFnbOrderMutation: `generateIdempotencyKey('pos_ord')` di dalam
      mutationFn.
    - src/api/inventory.api.ts useRecordMovementMutation: fallback
      `ui-${Date.now()}-${Math.random()...}` (DUPLIKAT generateIdempotencyKey dan lebih lemah). Hapus.
    - src/pages/pos/PosPage.tsx handleConfirmPayment: `generateIdempotencyKey('pay')` dibuat di setiap
      klik.
    - src/pages/products/components/RecordMovementModal.tsx tidak mengirim idempotencyKey sama sekali
      (RecordMovementInput di src/types/inventory.ts sudah punya field idempotencyKey; pakai itu).
    Yang harus dilakukan:
    a. Pakai SATU generator: `generateIdempotencyKey` di src/api/fnbOrder.api.ts. Hanya jika importnya
       dari inventory.api.ts menimbulkan import melingkar, pindahkan fungsi itu SAJA ke
       src/utils/idempotency.ts dan arahkan semua pemanggil ke sana. Jangan membuat generator kedua.
    b. Mutation menerima key lewat variables (wajib); tidak ada lagi pembuatan key di dalam mutationFn.
    c. Komponen pemicu menyimpan key di useRef: dibuat saat aksi dimulai (modal dibuka / checkout
       dimulai / order yang dibayar dipilih), DIPAKAI ULANG saat retry atau klik ulang dengan payload
       sama, dan di-reset (1) setelah sukses, (2) saat payload berubah.
    d. Tombol buat order/bayar/catat mutasi dinonaktifkan selama mutation isPending.
    Selesai bila: tidak ada lagi `Date.now()`/`Math.random()` untuk key di src/api, dan pencarian
    `generateIdempotencyKey` hanya menemukan SATU definisi.
    
    === TEMUAN 2: rate limit (429), cooldown di UI dan realtime ===
    Kontrak backend FINAL: HTTP 429, header `Retry-After: <detik>` (integer >= 1, sudah terbuka lewat
    CORS), body ApiResponse {success:false, code:429, message:"Terlalu banyak permintaan. Coba lagi
    dalam N detik."}. Tidak ada header X-RateLimit-*. Batas realtime-ticket: 10 per menit per user.
    client.ts sudah membuat RateLimitError(retryAfterSeconds) dan queryClient sudah menghormatinya
    untuk query. Tambahkan yang kurang, secukupnya:
    a. Cooldown terpusat: di interceptor 429 (client.ts) simpan waktu `rateLimitedUntil` (module-level)
       dan dispatch event window (ikuti pola 'auth:unauthorized' yang sudah ada). Cari dulu apakah
       hook/util serupa sudah ada; bila belum, buat SATU hook kecil useRateLimitCooldown yang
       mengembalikan sisa detik. Pakai untuk menonaktifkan tombol dan menampilkan sisa detik pada:
       buat order, bayar, catat mutasi stok, bulk update status. Jangan menambah state global lain.
    b. src/services/fnbRealtime.ts: blok catch menelan semua error. Jika error adalah RateLimitError,
       jadwalkan reconnect dengan delay = max(backoff, retryAfterSeconds * 1000). Jangan reset
       `reconnectAttempts` langsung di onopen: reset hanya setelah koneksi bertahan stabil (>= 30
       detik; angka itu satu konstanta bernama). Koneksi yang buka-tutup berulang menghabiskan ticket.
    Selesai bila: setelah 429 tombol aksi nonaktif sampai Retry-After habis, dan reconnect realtime
    tidak mengulang tiket lebih cepat dari Retry-After.
    
    === TEMUAN 3: request paralel tak terbatas dan alur multi-langkah ===
    a. src/api/product.api.ts useBulkUpdateStatusMutation: `Promise.all` dengan N PUT sekaligus
       menabrak burst backend (60 request) dan membuat hasil setengah jalan. Ganti dengan batch kecil
       (5 paralel per gelombang; angka sebagai satu konstanta), pakai Promise.allSettled, hentikan
       gelombang berikutnya bila ada RateLimitError (tunggu cooldown), selalu invalidate query di
       finally, dan laporkan ke pengguna "X dari N berhasil, Y gagal".
    b. src/api/inventory.api.ts useDeleteOptionGroupMutation (GET grup produk -> PUT lepas -> DELETE)
       dan useCreateOptionGroupMutation (POST grup -> PUT pasang): JANGAN mengubah urutan atau
       endpoint (backend tidak punya endpoint atomik; jangan diciptakan). Tambahkan hanya penanganan
       gagal sebagian: bila langkah ke-2 gagal, tampilkan pesan jelas ("Grup dibuat tetapi gagal
       dipasang ke produk" / "Grup dilepas tetapi gagal dihapus"), tetap invalidate query terkait,
       dan jangan biarkan UI basi. Bila kamu menilai perlu endpoint atomik di backend, LAPORKAN sebagai
       rekomendasi, jangan dikerjakan.
    
    DI LUAR TUGAS (jangan dikerjakan): pemetaan 409 ke kelas error, pesan error ganda di
    getErrorMessage, penanganan request yang dibatalkan. Itu dicatat orkestrator terpisah.
    
    VERIFIKASI (jangan klaim lolos tanpa menjalankan)
    - Jalankan pnpm typecheck, oxlint, dan vitest untuk file yang kamu sentuh. Tempel ringkasan hasil
      asli.
    - Audit diri: cari ulang fungsi/tipe ganda yang baru kamu buat, `any` baru, import tak terpakai,
      dan pemanggilan apiClient langsung di komponen. Bandingkan dengan baseline di
      E:\Java\cashier-saas-ui-baseline-20261111\ untuk memastikan diff kecil dan tidak ada rewrite.
    - Bila sesuatu tidak bisa dijalankan atau dites (server tidak jalan, dsb), tulis
      "TIDAK DIVERIFIKASI" dan alasannya. Jangan menyiratkan hasil yang tidak kamu lihat.
    
    LAPORAN AKHIR (format tetap)
    1. Ringkasan satu paragraf: apa yang berubah dan apa yang belum.
    2. Per temuan dan per file: apa yang diubah dan mengapa (satu baris).
    3. "Yang sudah ada dan dipakai ulang" (daftar file/fungsi).
    4. "Yang baru dibuat dan alasannya" (sertakan hasil pencarianmu).
    5. Hasil verifikasi (perintah + hasil) dan hal yang TIDAK DIVERIFIKASI.
    6. Ketidakcocokan kontrak/rekomendasi untuk orkestrator (jangan disesuaikan diam-diam).
    
    Jika kamu sudah terlanjur melanggar aturan di atas: berhenti, kembalikan HANYA perubahanmu sendiri
    yang melanggar (pakai baseline), lalu lanjut dengan diff kecil. Jangan menyentuh perubahan orang
    lain yang tidak kamu buat.
```
