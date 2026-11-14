# Prompt Antigravity (Gemini): refactor UI KedaiMbo untuk pivot "kasir + asisten AI"

Dibuat oleh orchestrator backend pada 12 November 2026. Tempel seluruh blok **PROMPT** di bawah ke Antigravity Agent Manager, dengan workspace `E:\Java\cashier-saas-ui` (boleh baca `E:\Java\cashier-saas` hanya untuk kontrak, tidak boleh diubah).

Catatan untuk pemilik proyek sebelum menempel:
- Repo UI belum memakai git. Prompt ini mewajibkan backup folder dulu (Fase 0). Jangan lewati.
- Kontrak backend di bagian 3 dikerjakan di branch backend `refactor/pivot-cashier-ai` (TASK-074 dan TASK-075). Jalankan UI terhadap backend dari branch itu, atau pakai `VITE_API_MODE=mock` sampai branch tersebut di-merge.
- Prompt lama `PROMPT_FOR_FE_AGENT_FNB_API_INTEGRATION.md` masih menyebut meja/QR/realtime. Untuk bagian yang bertentangan, prompt ini yang berlaku.

---

## PROMPT

```
Kamu bekerja sebagai TIM agent Antigravity pada repo frontend React E:\Java\cashier-saas-ui (KedaiMbo).
Tugas: refactor besar fitur UI mengikuti pivot produk. Ikuti dokumen ini secara KETAT. Jika ada yang tidak jelas
atau bertentangan, BERHENTI dan tanyakan ke pengguna. Jangan menebak.

==================================================================
1. STRUKTUR TIM (WAJIB)
==================================================================
- Satu agent ORCHESTRATOR: model Gemini paling kuat yang tersedia di Antigravity (mis. Gemini 3 Pro).
  Orchestrator TIDAK menulis kode aplikasi. Tugasnya: menjalankan audit, membagi paket kerja (WP),
  menetapkan daftar file milik tiap WP, mereview diff dan laporan worker, menjalankan verifikasi,
  dan menulis laporan akhir.
- Beberapa agent WORKER (model Gemini, boleh varian lebih cepat). Setiap worker mengerjakan SATU WP,
  hanya menyentuh file yang ditetapkan untuk WP itu, lalu MELAPOR ke orchestrator.
- Aturan file: satu file hanya dimiliki satu WP. Dua worker tidak boleh mengubah file yang sama.
  Worker yang butuh file di luar daftarnya BERHENTI dan meminta orchestrator.
- Format laporan worker: file reports/pivot-ui/WP-<id>.md berisi (a) file yang diubah/dihapus,
  (b) simbol yang dipakai ulang (nama + path), (c) bukti audit Codebase Memory sebelum dan sesudah,
  (d) hasil grep, (e) hasil verifikasi yang dijalankan, (f) sisa pekerjaan/risiko.
  Laporan diperbarui setiap checkpoint, bukan hanya di akhir.
- Orchestrator MENOLAK hasil worker jika ada perubahan di luar daftar file, kode duplikat,
  komponen duplikat, atau verifikasi gagal. Klaim worker bukan bukti: orchestrator cek diff sendiri.

==================================================================
2. KONTEKS PIVOT (keputusan pengguna, 12 Nov 2026, FINAL)
==================================================================
Produk berubah menjadi aplikasi KASIR (POS) biasa untuk F&B kecil. Pembeda utamanya nanti asisten AI
untuk owner (fitur AI BUKAN bagian tugas ini).
DIHAPUS dari UI (hapus kodenya, bukan disembunyikan, bukan feature flag):
  - Pemesanan pelanggan lewat QR dan semua halaman/panggilan publik (/public/**).
  - Antrean dapur / KDS (CoffeeKdsQueue dan semua aksi prepare/ready/complete).
  - Meja (dining tables): CRUD meja, rotate QR, pilihan meja di POS, tableId/tableName di tipe.
  - Real-time WebSocket: services/fnbRealtime.ts, hooks/useFnbRealtime.ts dan pemakaiannya.
DIPERTAHANKAN: POS, hold bill, pembayaran (tunai, non-tunai manual, mock gateway), struk, diskon,
transaksi, produk/kategori, option group, inventori/resep, pengaturan, audit viewer, laporan, onboarding.
Status order baru: OPEN (ditahan/belum dibayar), COMPLETED (sudah dibayar), CANCELLED.
HOLD BILL PINDAH KE SERVER: bill yang ditahan adalah order OPEN + UNPAID di backend.
localStorage untuk held orders DIHAPUS.
Tanpa WebSocket: daftar order di-refresh dengan polling 15 detik + refetch saat window fokus +
invalidate setelah mutasi sendiri.
Tidak ada redesign UI. Layout, token warna, dan gaya visual tetap. Yang berubah hanya fitur.

==================================================================
3. KONTRAK BACKEND YANG HARUS DIIKUTI (prefix /api/v1, envelope ApiResponse<T>)
==================================================================
Route yang DIHAPUS (UI tidak boleh memanggilnya lagi):
  GET/POST /public/**                                   (menu QR, order publik, status publik)
  /merchant/outlets/{outletId}/fnb/tables[/**]           (CRUD meja, rotate-qr)
  /merchant/outlets/{outletId}/fnb/qr atau route QR outlet lain
  /cashier/outlets/{outletId}/fnb/kitchen/**             (antrean, prepare, ready, complete)
  POST /cashier/outlets/{outletId}/fnb/realtime-ticket dan WebSocket GET /fnb/realtime
Route yang BERUBAH:
  POST /cashier/outlets/{outletId}/fnb/orders            body TANPA tableId.
       Header Idempotency-Key wajib (pakai generateIdempotencyKey yang sudah ada).
       Body: { serviceType: 'DINE_IN'|'TAKEAWAY', customerLabel: string,
               items: [{ productId, quantity, note?, optionIds: string[] }] }
  Response order (FnbStaffOrderResponse) TANPA tableId dan tableName. status: 'OPEN'|'COMPLETED'|'CANCELLED'.
  GET  /cashier/outlets/{outletId}/fnb/orders?status=OPEN&paymentStatus=UNPAID  = daftar bill yang ditahan.
Route BARU:
  PUT  /cashier/outlets/{outletId}/fnb/orders/{orderId}/items
       Body sama dengan POST order (serviceType, customerLabel, items). Tanpa Idempotency-Key.
       Mengganti SELURUH item order OPEN+UNPAID; total dihitung server. Response: FnbStaffOrderResponse.
       409 jika order sudah dibayar/dibatalkan, ada mock-gateway attempt yang tertunda, atau order sudah
       punya diskon (pesan dari server ditampilkan apa adanya). 404 jika order tidak ada.
Route TETAP: pembayaran POST .../orders/{orderId}/payments, resolve mock payment, cancel
  POST .../orders/{orderId}/cancel (alasan wajib), diskon POST /merchant/.../orders/{orderId}/discount,
  struk GET .../orders/{orderId}/receipt, detail GET .../orders/{orderId}.
Error 429 (rate limit) dan 409 tetap ditangani seperti sekarang (useRateLimitCooldown, toast error).
Sumber kontrak bila ragu (baca saja): E:\Java\cashier-saas\tasks\TASK-074.md, TASK-075.md,
E:\Java\cashier-saas\.ai\DECISIONS.md bagian "Pivot".

==================================================================
4. ATURAN KERAS ANTI-DUPLIKASI (pelanggaran = hasil ditolak)
==================================================================
1. DILARANG menulis kode duplikat. Sebelum menulis fungsi, hook, tipe, util, konstanta, atau komponen,
   WAJIB cari padanannya dengan Codebase Memory MCP (bagian 5). Jika sudah ada: PAKAI ULANG.
   Jika hampir sama: perluas yang ada secara minimal (prop/parameter opsional), jangan salin.
2. DILARANG menduplikasi komponen. Tidak boleh ada komponen baru yang fungsinya sama dengan yang ada.
   Komponen yang sudah ada dan wajib dipakai ulang (cek dulu isinya):
   - src/components/common: Modal, Button, Badge, Input, Select, Table, Pagination, BrandLogo,
     OutletSwitcherModal, EmailInput, PhoneInput.
   - src/components/feedback (loading/empty), src/components/notification (toast, ErrorModal).
   - src/pages/pos/components: HeldOrdersDrawer, PaymentModal, PaymentSuccessModal, ItemNotesModal,
     VariantCustomizationModal, PromoVoucherModal.
   - Sudah ada dua keluarga tombol/input (components/common/Button.tsx vs components/ui/button.tsx,
     common/Input.tsx vs ui/input.tsx). JANGAN membuat yang ketiga. Pakai yang sudah dipakai oleh file
     di sekitarnya. Jangan menggabungkan keduanya dalam tugas ini (bukan scope); cukup catat di laporan.
3. Hook/util yang wajib dipakai ulang: hook di src/api/fnbOrder.api.ts (useFnbStaffOrdersQuery,
   useFnbOrderDetailQuery, useCreateFnbOrderMutation, usePayFnbOrderMutation, useCancelFnbOrderMutation,
   useResolveMockPaymentMutation, useGrantDiscountMutation, useFnbReceiptQuery, fnbOrderKeys,
   generateIdempotencyKey), src/api/client.ts, src/api/response.ts (unwrap ApiResponse),
   src/api/queryClient.ts, src/hooks (useDebounce, useUrlFilters, useRateLimitCooldown),
   src/utils (formatCurrency, formatDate, cn, storage), src/language (kamus id/en),
   src/constants/brand.ts.
4. Hook baru hanya SATU: useReplaceFnbOrderItemsMutation di src/api/fnbOrder.api.ts, dengan pola yang
   persis sama seperti useCreateFnbOrderMutation (apiClient, unwrap, invalidate fnbOrderKeys).
   Mock adapter-nya ditambahkan di src/api/mock/fnbOrder.mock.ts mengikuti pola adapter yang ada.
5. Hapus, jangan matikan: tidak ada kode mati, tidak ada komentar "removed", tidak ada flag, tidak ada
   ekspor yang tidak dipakai, tidak ada key i18n yang tidak dipakai. Hapus import dan ekspor index terkait.
6. Tidak boleh menambah dependency npm. Tidak boleh mengubah token desain, layout shell, atau branding
   (ikuti GEMINI.md dan MEMORY.md di repo UI).
7. Tidak boleh mengubah backend E:\Java\cashier-saas. Tidak boleh mengakses database.
8. Tidak boleh tipe `any` baru, tidak boleh `// @ts-ignore`, tidak boleh menonaktifkan aturan lint.
9. Uang tetap dihitung server. UI hanya menampilkan total dari response; jangan menghitung pajak sendiri
   untuk order yang dikirim ke server.
10. Teks UI lewat src/language (id dan en), jangan hardcode.

==================================================================
5. AUDIT KODE DENGAN CODEBASE MEMORY MCP (WAJIB, SEBELUM DAN SESUDAH)
==================================================================
Nama project indeks: cashier-saas-ui, root E:/Java/cashier-saas-ui.
FASE AUDIT AWAL (orchestrator, sebelum WP apa pun):
 a. index_repository (mode full, name cashier-saas-ui) agar indeks segar; cek index_status.
 b. get_architecture untuk peta modul.
 c. Untuk setiap simbol yang akan dihapus: search_graph + trace_path (callers dan callees), lalu
    konfirmasi dengan grep teks (indeks bisa luput membaca JSX/route). Simbol minimal:
    useFnbRealtime, fnbRealtime, CoffeeKdsQueue, useFnbTablesQuery, useCreateFnbTableMutation,
    useUpdateFnbTableMutation, useRotateFnbTableQrMutation, fnbKitchenKeys, useFnbKitchenOrdersQuery,
    usePrepareKitchenOrderMutation, useReadyKitchenOrderMutation, useCompleteKitchenOrderMutation,
    FnbTableResponse, tableId, tableName, HELD_ORDERS_STORAGE_KEY, HeldOrder, INITIAL_HELD_ORDERS,
    status NEW/CONFIRMED/PREPARING/READY, kata QR/KDS/meja/table di src/language dan halaman landing.
 d. Untuk setiap hal yang akan DITULIS (hook replace items, pemetaan order -> cart, label status,
    polling): search_graph/search_code untuk padanan yang sudah ada. Contoh: fungsi pemetaan
    FnbStaffOrderResponse -> CartItem atau CartItem -> FnbPosOrderRequest mungkin sudah ada di
    PosPage atau mock; jika ada, dipakai ulang, bukan ditulis lagi.
 e. Catat juga duplikasi lama yang ditemukan (jangan diperbaiki kecuali masuk WP).
 f. Tulis hasilnya ke reports/pivot-ui/AUDIT.md: tabel simbol -> file -> pemanggil -> keputusan
    (hapus / ubah / pakai ulang), daftar kandidat reuse, daftar file per WP.
    Tunjukkan AUDIT.md ke pengguna dan tunggu persetujuan sebelum worker mulai.
AUDIT AKHIR (orchestrator, setelah semua WP):
 g. index_repository ulang, lalu search_graph untuk semua simbol di (c): hasil harus nol (kecuali yang
    dinyatakan tetap). Ulangi grep teks di src dan tests.
 h. Cek duplikasi baru: bandingkan daftar fungsi/komponen sebelum dan sesudah (search_graph per nama
    file yang diubah). Setiap fungsi/komponen BARU harus punya alasan di laporan dan tidak boleh punya
    padanan lama.
Worker juga WAJIB memakai search_graph/search_code sebelum menulis apa pun di WP-nya, dan
mencantumkan query + hasilnya di laporan WP.

==================================================================
6. FASE DAN PAKET KERJA (urutan wajib)
==================================================================
FASE 0, orchestrator: backup. Salin folder E:\Java\cashier-saas-ui ke
  E:\Java\cashier-saas-ui-backup-20261112 TANPA node_modules dan dist. Konfirmasi ke pengguna bahwa
  backup ada. Repo ini belum memakai git, jadi backup adalah satu-satunya jalan kembali.
  Jalankan baseline: npx tsc -b, npm run lint, npm run build, npm run test. Catat hasilnya
  (error lama bukan tanggung jawab tugas ini, tetapi tidak boleh bertambah).
FASE 1, orchestrator: audit awal (bagian 5 a-f), tunggu persetujuan pengguna.
FASE 2, WP-A (satu worker, harus selesai dan direview sebelum fase 3). Lapisan API/tipe/mock/i18n:
  src/api/fnbOrder.api.ts, src/api/mock/fnbOrder.mock.ts, src/types/fnbOrder.ts, src/types/pos.ts,
  src/language/id.ts, src/language/en.ts, src/hooks/index.ts, hapus src/hooks/useFnbRealtime.ts dan
  src/services/fnbRealtime.ts, serta test di tests/unit yang menyentuh file tersebut.
  - Hapus hook meja dan dapur, query key tables/kitchen, dan invalidate fnbOrderKeys.tables di semua mutasi.
  - FnbOrderStatus = 'OPEN' | 'COMPLETED' | 'CANCELLED'. Hapus tableId/tableName dari tipe request/response.
  - Tambah useReplaceFnbOrderItemsMutation (bagian 4 butir 4) + mock adapter + unit test
    tests/unit/api/fnbOrder.test.ts (pola test api yang ada).
  - useFnbStaffOrdersQuery menerima opsi polling: refetchInterval 15000 dan refetchOnWindowFocus true
    hanya untuk pemanggil daftar order (bukan global di queryClient). Gunakan satu konstanta
    ORDER_LIST_POLL_MS = 15000 di file yang sudah ada (mis. src/api/fnbOrder.api.ts), jangan file baru.
  - Label status di language: OPEN "Ditahan / Belum dibayar" (en "Open / Unpaid"),
    COMPLETED "Selesai" (en "Completed"), CANCELLED "Dibatalkan" (en "Cancelled"). Hapus key KDS/meja/QR
    yang tidak lagi dipakai (setelah fase 3 tidak boleh ada key mati).
  - Hapus tipe HeldOrder jika setelah fase 3 tidak dipakai (koordinasikan: WP-A menyiapkan, WP-B
    menghapus pemakaian, orchestrator memastikan hilang di audit akhir).
FASE 3, paralel setelah WP-A disetujui:
  WP-B (POS dan hold bill): src/pages/pos/PosPage.tsx, src/pages/pos/components/HeldOrdersDrawer.tsx,
    src/pages/pos/components/PaymentModal.tsx, src/pages/pos/components/PaymentSuccessModal.tsx,
    tests/unit/pos/**.
    - Hapus useFnbRealtime(), pilihan meja, HELD_ORDERS_STORAGE_KEY, INITIAL_HELD_ORDERS dan semua
      localStorage held orders (hapus juga key lama sekali saat load: localStorage.removeItem lewat
      utils/storage yang sudah ada).
    - Hold: tombol hold memanggil useCreateFnbOrderMutation (order OPEN) dengan Idempotency-Key yang
      dibuat sekali per percobaan hold (pola holdOrderIdempotencyKeyRef yang sudah ada dipakai ulang).
    - HeldOrdersDrawer membaca useFnbStaffOrdersQuery({ status: 'OPEN', paymentStatus: 'UNPAID' })
      dengan polling 15 detik. Tidak ada sumber data lain.
    - Resume: muat detail order (useFnbOrderDetailQuery) ke cart, simpan orderId aktif di state.
      Jika cart diubah lalu disimpan/dibayar: panggil useReplaceFnbOrderItemsMutation dulu, lalu bayar
      dengan usePayFnbOrderMutation memakai total dari response terbaru. Jika cart tidak berubah:
      langsung bayar. Tombol dinonaktifkan selama mutasi berjalan (cegah double submit).
    - Hapus bill yang ditahan = useCancelFnbOrderMutation dengan alasan wajib (pakai Modal yang ada
      untuk input alasan). Tidak ada penghapusan lokal.
    - 409 dari PUT items (mis. sudah ada diskon) ditampilkan dengan toast pesan server; jangan retry otomatis.
    - Badge jumlah antrean (heldQueue) memakai jumlah dari query server saja.
  WP-C (dashboard, transaksi, copy): src/pages/dashboard/DashboardPage.tsx, hapus
    src/pages/dashboard/components/CoffeeKdsQueue.tsx, src/pages/dashboard/components/Coffee*Modal.tsx
    (hanya bagian status/meja/KDS), src/pages/transactions/**, src/pages/landing/LandingPage.tsx dan
    src/pages/onboarding/OnboardingPage.tsx (HANYA teks yang menjanjikan QR order/KDS/meja; tanpa
    perubahan layout), src/routes/** jika ada route QR/meja/dapur.
    - Filter dan badge status transaksi hanya OPEN/COMPLETED/CANCELLED.
    - Daftar order di dashboard/transaksi memakai polling 15 detik yang sama (opsi dari WP-A).
  File lain yang ternyata perlu diubah (mis. src/components/common/Table.tsx tidak terkait meja dan
  JANGAN disentuh: kata "Table" di sana adalah tabel data) diputuskan orchestrator, bukan worker.
FASE 4, orchestrator: review diff tiap WP terhadap daftar file, audit akhir (bagian 5 g-h),
  verifikasi (bagian 7), laporan akhir reports/pivot-ui/ORCHESTRATOR.md, lalu tunjukkan ke pengguna.

==================================================================
7. VERIFIKASI (orchestrator menjalankan sendiri, bukan percaya laporan)
==================================================================
- npx tsc -b: 0 error.  npm run lint: 0 error.  npm run build: sukses.  npm run test: semua lulus,
  termasuk test baru untuk useReplaceFnbOrderItemsMutation dan alur hold bill.
- Grep di src dan tests harus nol untuk: fnbRealtime, useFnbRealtime, CoffeeKdsQueue, kitchen,
  /fnb/tables, rotate-qr, realtime-ticket, /public/, tableId, tableName, HELD_ORDERS_STORAGE_KEY,
  'NEW', 'CONFIRMED', 'PREPARING', 'READY' (sebagai status order).
- Uji manual dengan backend branch refactor/pivot-cashier-ai (atau VITE_API_MODE=mock):
  buat order -> hold -> muncul di drawer -> resume -> ubah item -> bayar tunai -> struk -> status
  COMPLETED; hold -> hapus dengan alasan -> CANCELLED; perangkat/tab kedua melihat bill ditahan dalam
  kurang dari 15 detik; 409 dan 429 tampil sebagai pesan, bukan crash.
- Laporan akhir: tabel file -> diubah/dihapus/baru (dengan alasan untuk setiap file baru), hasil
  verifikasi apa adanya (termasuk yang gagal), duplikasi lama yang ditemukan, risiko tersisa.

==================================================================
8. LARANGAN RINGKAS
==================================================================
Jangan: menulis kode/komponen duplikat; membuat file baru tanpa alasan tertulis di AUDIT.md; menambah
dependency; redesign; mengubah backend; akses database; menyimpan held orders di browser; memanggil
route yang dihapus; menghitung total di client untuk order server; melewati fase audit; melaporkan
selesai sebelum verifikasi bagian 7 lulus.
```
