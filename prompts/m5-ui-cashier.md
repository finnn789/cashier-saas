# Prompt UI M5: kasir cepat, pembayaran tercatat, struk, kas kecil, laporan harian (KedaiMbo)

Dibuat oleh worker W7 TASK-078 pada 14 November 2026. Tempel seluruh blok **PROMPT** ke agent frontend dengan workspace `E:\Java\cashier-saas-ui` (boleh membaca `E:\Java\cashier-saas` hanya untuk kontrak, tidak boleh diubah).

Catatan untuk pemilik proyek:
- Kontrak di bawah sudah ada di backend branch `fix/flow-controls-a` (TASK-078, belum di-commit dan belum dijalankan end to end). Jalankan UI terhadap backend dari branch itu, atau pakai `VITE_API_MODE=mock` dan tambahkan adapter mock yang mengikuti pola yang ada.
- Prompt ini melengkapi `prompts/refactore_feature_and_uui.md` (pivot) dan mengganti bagian yang bertentangan: **gateway mock dan alur resolve DIHAPUS** dari backend. Metode pembayaran hanya `CASH` dan `MANUAL_NON_CASH`.
- Seed demo tidak membuat label pembayaran. Outlet baru dari onboarding otomatis punya QRIS, Transfer, Debit; pada stack demo, owner membuat label dulu lewat halaman pengaturan (atau `POST .../payment-labels`).

---

## PROMPT

```
Kamu bekerja pada repo frontend React E:\Java\cashier-saas-ui (KedaiMbo): React 19, Vite, TypeScript,
Tailwind CSS 4, TanStack Query 5, react-hook-form + zod, axios (src/api/client.ts, src/api/response.ts
meng-unwrap ApiResponse). Ikuti GEMINI.md/MEMORY.md di repo UI (token desain, branding, bahasa Indonesia).
Tugas: tambahkan fitur milestone M5 di bawah. Jika ada yang tidak jelas atau bertentangan, BERHENTI dan
tanyakan ke pengguna. Jangan menebak kontrak.

==================================================================
1. ATURAN KERAS
==================================================================
1. PAKAI ULANG, JANGAN TULIS ULANG. Perluas halaman/komponen yang ada (PosPage, PaymentModal,
   PaymentSuccessModal, HeldOrdersDrawer, panel/halaman shift bila sudah ada, halaman pengaturan,
   transaksi). Tidak ada rewrite halaman, tidak ada redesign, tidak ada komponen ketiga untuk tombol/input
   (pakai keluarga yang dipakai file di sekitarnya).
2. DILARANG membuat API client baru atau instance axios baru. Semua panggilan lewat src/api/client.ts dan pola
   hook yang ada di src/api (mis. fnbOrder.api.ts: useFnbStaffOrdersQuery, usePayFnbOrderMutation,
   useFnbReceiptQuery, fnbOrderKeys, generateIdempotencyKey). Hook baru ditambahkan ke file api yang sudah
   relevan dengan pola yang sama (apiClient, unwrap, invalidate query key); file api baru hanya bila
   domainnya benar-benar belum punya file (shift, label pembayaran).
3. Sebelum menulis fungsi/hook/komponen/tipe: cari padanannya (Codebase Memory MCP project cashier-saas-ui,
   lalu grep). Jika ada, pakai atau perluas secara minimal.
4. Hapus semua sisa gateway mock di UI: useResolveMockPaymentMutation, pilihan metode MOCK_GATEWAY,
   tampilan provider/attempt/gatewayOutcome/providerReference, "SIMULASI PEMBAYARAN", dan key i18n terkait
   (setelah dipakai nol kali). Hapus, jangan disembunyikan.
5. Uang dihitung server. UI menampilkan total dari response dan hanya menghitung kembalian untuk tampilan
   tunai (amountTendered - total); server tetap menghitung kembalian yang disimpan.
6. Idempotency key dibuat SEKALI per percobaan (useRef/state, bukan di dalam mutationFn) dan dipakai ulang
   saat retry yang sama; buat baru untuk operasi baru. Berlaku untuk: checkout, pembayaran
   (body.idempotencyKey), pengeluaran kas kecil (header Idempotency-Key). Format: 16-128 karakter
   [A-Za-z0-9_-]; pakai generateIdempotencyKey yang ada.
7. Teks UI lewat src/language (id dan en). Tidak ada tipe any baru, @ts-ignore, atau dependency npm baru.
8. Jangan mengubah backend. Jangan menjalankan perintah yang mengubah data tanpa izin pengguna.
9. Verifikasi di akhir: npx tsc -b, npm run lint, npm run build, npm run test (error lama tidak boleh
   bertambah). Tulis unit test untuk hook baru dan logika uang cepat/kembalian mengikuti pola test yang ada.

==================================================================
2. KONTRAK BACKEND (prefix /api/v1, semua respons berbentuk ApiResponse<T>: {success, code, message, data})
==================================================================
Peran: OWNER = OWNERMERCHANT. Route /cashier/... boleh OWNER dan CASHIER. Route /merchant/... hanya OWNER.
Kesalahan validasi dan aturan bisnis = 400; konflik/aturan status = 409; tidak ada/di luar tenant = 404;
tanpa akses = 403; rate limit = 429. Tampilkan pesan server apa adanya (toast/ErrorModal yang ada).
Uang = angka desimal 2 digit. Waktu = ISO 8601 (tampilkan dengan zona waktu outlet).

--- 2.1 Label pembayaran non-tunai ---
GET  /cashier/outlets/{outletId}/fnb/payment-labels            (OWNER, CASHIER) label AKTIF saja, urut displayOrder lalu nama.
     data: [{ id, name, active, displayOrder }]
GET  /merchant/outlets/{outletId}/fnb/payment-labels            (OWNER) semua label termasuk nonaktif.
POST /merchant/outlets/{outletId}/fnb/payment-labels            (OWNER) body { name (wajib, maks 40), displayOrder? (0-9999) } -> 201 data = label.
     Label baru selalu aktif; displayOrder default = jumlah label + 1.
PUT  /merchant/outlets/{outletId}/fnb/payment-labels/{labelId}  (OWNER) body { name, displayOrder?, active? } -> data = label.
     displayOrder/active yang dihilangkan mempertahankan nilai lama. Tidak ada DELETE: nonaktifkan dengan active=false.
Error: 409 "Label sudah ada." (nama kembar, tanpa membedakan huruf besar/kecil), 409 "Jumlah label maksimal 20.",
400 "Nama label tidak valid.", 404 label di luar outlet.
Default untuk outlet: QRIS, Transfer, Debit.

--- 2.2 Pembayaran (hanya dicatat; tidak ada gateway) ---
POST /cashier/outlets/{outletId}/fnb/orders/{orderId}/payments  (OWNER, CASHIER)
     body { method: "CASH" | "MANUAL_NON_CASH", amountTendered: number (>= 0, 2 desimal), labelId?: uuid, idempotencyKey: string (wajib, maks 128) }
     - CASH: amountTendered >= total, labelId DILARANG (400 "Pembayaran tunai tidak memakai label.").
       Kurang dari total: 400 "Uang tunai kurang dari total pesanan."
     - MANUAL_NON_CASH: amountTendered HARUS sama dengan total (400 "Pembayaran non-tunai harus sesuai total pesanan.")
       dan labelId wajib berupa label aktif outlet (400 "Pilih label pembayaran non-tunai.").
     - Wajib ada shift terbuka milik pembayar: 409 "Buka shift terlebih dahulu sebelum menerima pembayaran."
     - 409 "Pesanan sudah dibayar." / 409 "Kunci pembayaran telah digunakan." (key yang sama dengan isi berbeda).
       Retry key yang sama dengan isi sama mengembalikan hasil lama.
     data: { orderId, method, amountPaid, changeAmount, methodLabel? (hanya non-tunai), receiptNumber, paidAt, stockWarnings: string[] }
     receiptNumber berbentuk INV-YYYYMMDD-NNNN (berurutan per outlet per hari, zona waktu outlet).
POST /cashier/outlets/{outletId}/fnb/orders/{orderId}/cancel     body { reason (wajib, maks 500) } (pesanan OPEN/UNPAID saja; kasir tidak boleh membatalkan pesanan berdiskon -> 403)
POST /merchant/outlets/{outletId}/fnb/orders/{orderId}/refund   (OWNER) header Idempotency-Key, body { reason } (refund penuh pesanan lunas)
Route resolve mock payment TIDAK ADA lagi (jangan dipanggil).

--- 2.3 Pesanan ---
POST /cashier/outlets/{outletId}/fnb/orders                     header Idempotency-Key (wajib)
PUT  /cashier/outlets/{outletId}/fnb/orders/{orderId}/items     tanpa Idempotency-Key (ganti seluruh item pesanan OPEN/UNPAID)
     body { serviceType: "DINE_IN"|"TAKEAWAY", customerLabel?: string (OPSIONAL, maks 80, kosong -> null),
            orderNote?: string (OPSIONAL, catatan level pesanan, maks 500, kosong -> null),
            items: [{ productId, quantity (1-100), note? (maks 500), optionIds: uuid[] }] }  (maks 50 item)
GET  /cashier/outlets/{outletId}/fnb/orders                     query: page (0..), size (1..50, default 20), status?, paymentStatus?, source?,
     from? + to? (ISO date yyyy-MM-dd menurut zona waktu outlet; harus KEDUANYA atau tidak sama sekali; maks 366 hari; to >= from),
     orderNumber? (cocok persis setelah di-trim).
     400 "Filter tanggal harus menyertakan from dan to." / "Rentang tanggal pesanan tidak valid."
     data: { orders: [FnbStaffOrderResponse], page, size, hasNext }
GET  /cashier/outlets/{outletId}/fnb/orders/{orderId}           data = FnbStaffOrderResponse
     FnbStaffOrderResponse: { orderId, orderNumber, source, serviceType, customerLabel?, orderNote?, status ("OPEN"|"COMPLETED"|"CANCELLED"),
       paymentStatus ("UNPAID"|"PAID"|"REFUNDED"), createdAt, subtotal, taxRate, taxAmount, serviceChargeRate, serviceChargeAmount,
       discountAmount, total, items: [{ itemId, productName, unitPrice, quantity, lineTotal, note?, options: [...] }] }
     (field null tidak dikirim.)
GET  /cashier/outlets/{outletId}/fnb/products                   data: [{ id, categoryId, name, description, price, imageUrl, available, archived, displayOrder }]
GET  /cashier/outlets/{outletId}/fnb/categories                 (sudah dipakai POS)

--- 2.4 Shift, saran modal awal, kas kecil ---
GET  /cashier/outlets/{outletId}/fnb/shifts/opening-suggestion  data: { openingCash, sourceShiftId? } (uang terhitung shift tertutup terakhir di outlet; 0.00 dan null bila belum ada)
POST /cashier/outlets/{outletId}/fnb/shifts                     body { openingCash >= 0 } -> 201 FnbShiftResponse. 409 "Anda masih memiliki shift terbuka." bila sudah ada.
GET  /cashier/outlets/{outletId}/fnb/shifts/current             FnbShiftResponse shift terbuka milik pemanggil dengan ringkasan langsung; 404 bila tidak ada.
POST /cashier/outlets/{outletId}/fnb/shifts/current/expenses    header Idempotency-Key, body { amount (> 0, 2 desimal), description (wajib, maks 200) }
     -> 201 data: { id, amount, description, recordedAt, recordedBy (nama lengkap) }
     409 "Buka shift terlebih dahulu sebelum mencatat pengeluaran." bila tidak ada shift terbuka;
     409 "Idempotency-Key telah digunakan untuk pengeluaran berbeda." bila key sama tetapi isi berbeda.
GET  /cashier/outlets/{outletId}/fnb/shifts/current/expenses    data: [expense] terlama dulu; 404 bila tidak ada shift terbuka.
POST /cashier/outlets/{outletId}/fnb/shifts/current/close       body { countedCash >= 0, note? (maks 500) } -> FnbShiftResponse final. 409 "Shift sudah ditutup."
GET  /merchant/outlets/{outletId}/fnb/shifts?from=&to=&page=&size=   (OWNER) data { shifts, page, size, hasNext }
GET  /merchant/outlets/{outletId}/fnb/shifts/{shiftId}          (OWNER)
POST /merchant/outlets/{outletId}/fnb/shifts/{shiftId}/close    (OWNER) body sama; tutup paksa
     FnbShiftResponse: { id, status ("OPEN"|"CLOSED"), userId, fullName, openingCash, openedAt, closedAt?, expectedCash, countedCash?, cashDifference?,
       closingNote?, paymentCount, totalsByMethod: [{ method, methodLabel? (non-tunai), count, amount }],
       cashIn, cashRefunds, expensesTotal, expenses?: [expense] (ada di open/current/detail/close, tidak di daftar owner), openOrderCount }
     expectedCash = openingCash + cashIn - cashRefunds - expensesTotal (dihitung server; boleh negatif).
     Semua pesanan OPEN yang masih ada saat tutup shift hanya jadi peringatan lewat openOrderCount (bukan penghalang).

--- 2.5 Struk dan pengaturan ---
GET  /cashier/outlets/{outletId}/fnb/orders/{orderId}/receipt   untuk pesanan PAID atau REFUNDED (CANCELLED/UNPAID = 404)
     data: { receiptNumber, orderNumber, businessName, merchantName, logoUrl?, outletName, outletAddress?, outletPhone?, currency, timeZone,
       paidAt (waktu lokal outlet), items: [{ productName, quantity, unitPrice, lineTotal, options }], subtotal, discountAmount, taxAmount,
       serviceChargeAmount, total, method, methodLabel?, tendered, changeAmount, receivedBy (kasir), customerLabel?, orderNote?,
       receiptFooter?, refunded (boolean), refundedAt?, refundReason?, html (sudah di-escape, berisi CSS cetak, penanda REFUND, dan footer) }
GET  /merchant/outlets/{outletId}/business-settings              (OWNER) data: { outletId, businessName, logoUrl?, currency, address?, phoneNumber?, taxRate,
       serviceChargeRate, outletName, outletAddress?, outletPhoneNumber?, timeZone, receiptFooter? }
PUT  /merchant/outlets/{outletId}/business-settings              (OWNER) body = dokumen LENGKAP yang sama (mengganti seluruhnya; kirim kembali nilai yang ada)
       + receiptFooter? (opsional, maks 200; kosong/dihilangkan = footer dihapus; 400 "Footer struk maksimal 200 karakter.")

--- 2.6 Laporan owner (sudah ada, ada tambahan) ---
GET  /merchant/outlets/{outletId}/fnb/reports/sales              paymentBreakdown dan transactions kini membawa methodLabel (QRIS vs Transfer dst);
     topProducts tersedia untuk OWNER saja. CSV punya kolom "Payment Label".

==================================================================
3. FITUR YANG DIKERJAKAN
==================================================================
3.1 Buka shift dengan saran modal awal
  - Saat form buka shift tampil, panggil opening-suggestion dan isi kolom modal awal dengan openingCash
    (tampilkan keterangan "dari uang terhitung shift terakhir"). Pengguna boleh mengubahnya. Jika
    sourceShiftId null, isi 0.
  - Jika request gagal, biarkan kolom kosong/0 (jangan blokir buka shift).

3.2 Layar pembayaran (perluas PaymentModal, jangan ganti)
  - Dua pilihan saja: "Tunai" dan "Non-tunai". Hapus MOCK_GATEWAY.
  - Tunai: input uang diterima; tombol cepat "Uang pas" (= total), "20rb", "50rb", "100rb" (mengisi
    nominal diterima = nilai tombol; "Uang pas" = total). Tampilkan kembalian (diterima - total) dan nonaktifkan
    tombol bayar bila diterima < total. Kirim method CASH tanpa labelId.
  - Non-tunai: tampilkan pemilih label dari GET cashier payment-labels (mis. QRIS, Transfer, Debit), wajib memilih
    satu. Nominal = total (tidak dapat diubah, kirim amountTendered = total). Kirim method MANUAL_NON_CASH + labelId.
    Jika daftar label kosong: tampilkan pesan "Belum ada label pembayaran non-tunai. Minta pemilik menambahkannya
    di Pengaturan." dan nonaktifkan tombol (OWNER: tautan ke halaman label).
  - Cache query label dengan staleTime wajar; invalidate setelah owner mengubah label.
  - Setelah sukses: PaymentSuccessModal menampilkan nomor struk (receiptNumber), metode + label, kembalian,
    dan stockWarnings. Tombol "Cetak struk" bersifat OPSIONAL (3.6); transaksi selesai tanpa mencetak.

3.3 Kas kecil di panel shift
  - Di panel/halaman shift (perluas yang ada, atau tambahkan bagian di dalamnya): form "Catat pengeluaran"
    (nominal > 0, keterangan wajib, maks 200) dan daftar pengeluaran shift (GET current/expenses) dengan jumlah
    total. Tombol dinonaktifkan selama mutasi berjalan; Idempotency-Key per percobaan. Setelah sukses
    invalidate shift current dan daftar pengeluaran.

3.4 Tutup shift sebagai laporan harian
  - Layar tutup shift menampilkan ringkasan sebelum konfirmasi, dari GET current: jumlah transaksi
    (paymentCount), total per metode dan label (totalsByMethod: Tunai, QRIS, Transfer, Debit...), kas masuk (cashIn),
    refund tunai (cashRefunds), pengeluaran (expensesTotal + daftar), modal awal, kas seharusnya (expectedCash).
  - Kasir mengisi uang di laci (countedCash) dan catatan; setelah tutup, tampilkan hasil final dengan selisih
    (cashDifference) dan warna jelas untuk kurang/lebih.
  - Jika openOrderCount > 0: tampilkan peringatan "Masih ada N tagihan tertahan" (tidak memblokir).
  - Jangan menghitung ulang expectedCash di UI; tampilkan nilai server.

3.5 Pengaturan owner: label pembayaran dan footer struk
  - Halaman pengaturan yang ada: tambah bagian "Label pembayaran non-tunai" (daftar semua label via merchant
    GET, tambah, ubah nama/urutan, aktif/nonaktif; tanpa hapus) dan kolom "Footer struk" (maks 200) pada
    form pengaturan bisnis. Tampilkan pesan server untuk 409 (nama kembar, batas 20).
  - PUT business-settings mengganti seluruh dokumen: kirim kembali semua nilai yang sedang ada plus receiptFooter,
    jangan mengosongkan field lain secara tidak sengaja.

3.6 Tampilan cetak struk (opsional dicetak)
  - Setelah bayar dan dari daftar transaksi/pesanan: tombol "Lihat struk" / "Cetak struk" memuat GET receipt.
    Cetak hanya atas permintaan pengguna (window.print pada tampilan struk atau render field JSON dengan CSS cetak
    yang ada); jangan membuka dialog cetak otomatis.
  - Tampilkan: logo bila ada (HTTPS), nama usaha, alamat/telepon outlet, nomor struk dan nomor pesanan, waktu
    lokal, kasir, pelanggan dan catatan bila ada, item beserta opsi, subtotal, diskon, pajak, biaya layanan, total,
    metode + label, diterima, kembalian, footer.
  - Jika refunded = true: tampilkan penanda "REFUND" yang jelas (+ refundedAt dan refundReason).
  - Jika memakai field `html` dari server, render dengan aman (iframe sandbox atau srcdoc); isinya sudah di-escape
    server. Jangan menyisipkan html mentah ke DOM aplikasi. Boleh juga merender dari field JSON dan mengabaikan html.

3.7 Daftar pesanan dengan filter
  - Daftar pesanan/transaksi (perluas halaman yang ada): filter tanggal (from + to, kirim keduanya atau tidak
    sama sekali; batasi pilihan maks 366 hari), pencarian nomor pesanan (persis), plus filter status/pembayaran
    yang sudah ada. Pakai useUrlFilters dan useDebounce yang ada. Tampilkan umur tagihan yang ditahan (createdAt
    dibanding sekarang) di HeldOrdersDrawer.
  - Tampilkan 400 dari server apa adanya.

3.8 Kasir cepat di POS
  - Produk favorit/terlaris di atas: backend tidak menyediakan "terlaris" untuk kasir (topProducts hanya untuk
    OWNER di laporan). Gunakan penghitung lokal per outlet (jumlah produk ditambahkan ke keranjang, lewat
    utils/storage yang ada) untuk mengurutkan bagian "Favorit" di atas daftar produk; owner dapat memakai
    topProducts bila sudah memuat laporan. Jangan menambah endpoint atau menebak kontrak baru.
  - Pencarian dengan keyboard: pintasan "/" atau Ctrl+K memfokuskan kolom cari produk; Enter menambahkan produk
    pertama hasil filter ke keranjang bila hanya satu hasil; Esc mengosongkan pencarian. Jangan merusak input teks
    lain (abaikan pintasan saat fokus di input/textarea selain kolom cari).
  - Nama pelanggan OPSIONAL (hapus validasi wajib di form/skema zod) dan tambah kolom "Catatan pesanan"
    (maks 500) pada keranjang; kirim kosong sebagai string kosong atau tidak dikirim (server menyimpan null).
    Tampilkan keduanya di detail pesanan dan struk.

==================================================================
4. PENANGANAN ERROR (wajib)
==================================================================
- 409 "Buka shift terlebih dahulu ..." (pembayaran atau pengeluaran): tampilkan pesan server dan tawarkan
  tombol "Buka shift" yang membuka form buka shift (3.1); setelah shift terbuka, pembayaran dapat diulang dengan
  key yang SAMA (isi sama). Jangan retry otomatis.
- 400 label ("Pilih label pembayaran non-tunai.", "Pembayaran tunai tidak memakai label.", "Pembayaran non-tunai
  harus sesuai total pesanan."): tampilkan di modal pembayaran, jangan menutup modal; muat ulang daftar label
  (label bisa dinonaktifkan owner saat modal terbuka).
- 409 "Pesanan sudah dibayar." atau "Kunci pembayaran telah digunakan.": muat ulang detail pesanan, jangan
  membuat key baru otomatis; pada kasus kedua minta kasir memulai ulang pembayaran (key baru) setelah memeriksa
  status pesanan.
- 409 dari PUT items (pesanan sudah dibayar/dibatalkan/berdiskon): toast pesan server, tanpa retry otomatis.
- 429: ikuti useRateLimitCooldown yang ada.
- 404 pada shift/current atau expenses artinya tidak ada shift terbuka: tampilkan keadaan "Belum ada shift", bukan error.

==================================================================
5. LAPORAN AKHIR
==================================================================
Tulis reports/m5-ui/REPORT.md: file yang diubah/dihapus, simbol yang dipakai ulang (nama + path), hasil
pencarian duplikasi, hasil tsc/lint/build/test, fitur yang belum dikerjakan beserta alasan, dan risiko.
Jangan menyatakan sesuatu "terverifikasi" bila tidak dijalankan.
```
