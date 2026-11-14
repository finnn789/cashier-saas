# Prompt UI M6: harga beli, HPP, stok opname, laporan laba kotor owner (KedaiMbo)

Dibuat oleh worker W6 TASK-079 pada 14 November 2026. Tempel seluruh blok **PROMPT** ke agent frontend dengan workspace `E:\Java\cashier-saas-ui` (boleh membaca `E:\Java\cashier-saas` hanya untuk kontrak, tidak boleh diubah).

Catatan untuk pemilik proyek:
- Kontrak di bawah ada di backend branch `fix/flow-controls-a` (TASK-079, migrasi V9). Belum dikompilasi/dijalankan end to end saat prompt ini ditulis; jalankan UI terhadap backend dari branch itu.
- Melengkapi `prompts/m5-ui-cashier.md`; aturan keras 1-9 di prompt itu (pakai ulang, tanpa API client baru, idempotency key per percobaan, teks lewat src/language, tanpa `any`, verifikasi tsc/lint/build/test) berlaku sama di sini.

---

## PROMPT

```
Kamu bekerja pada repo frontend React E:\Java\cashier-saas-ui (KedaiMbo): React 19, Vite, TypeScript,
Tailwind CSS 4, TanStack Query 5, react-hook-form + zod, axios (src/api/client.ts mengunwrap ApiResponse).
Ikuti GEMINI.md/MEMORY.md di repo UI (token desain, bahasa Indonesia). Tugas: fitur milestone M6 di bawah.
Jika ada yang tidak jelas atau bertentangan, BERHENTI dan tanyakan ke pengguna. Jangan menebak kontrak.

==================================================================
1. ATURAN KERAS
==================================================================
1. PAKAI ULANG: perluas halaman/komponen inventori dan laporan yang ada; tidak ada rewrite atau redesign.
2. Tanpa API client/instance axios baru; tambahkan hook ke file api inventori dan laporan yang sudah ada
   (pola apiClient, unwrap, invalidate query key). Cari padanan sebelum menulis fungsi/komponen/tipe.
3. Uang dan HPP dihitung server. UI hanya menampilkan nilai respons; satu-satunya hitungan lokal adalah
   selisih stok opname untuk pratinjau (hitung fisik - stok sistem).
4. Idempotency key dibuat SEKALI per percobaan (useRef/state, bukan di dalam mutationFn), dipakai ulang saat
   retry yang sama, dan diganti untuk operasi baru. Pakai generateIdempotencyKey yang ada.
5. Teks UI lewat src/language (id dan en). Tanpa any baru, @ts-ignore, atau dependency npm baru.
6. Jangan mengubah backend. Verifikasi akhir: npx tsc -b, npm run lint, npm run build, npm run test.

==================================================================
2. KONTRAK BACKEND (prefix /api/v1, respons ApiResponse<T>: {success, code, message, data})
==================================================================
Peran: OWNER = OWNERMERCHANT. /cashier/... boleh OWNER dan CASHIER; /merchant/... hanya OWNER.
Validasi/aturan bisnis = 400; konflik = 409; tidak ada/di luar tenant = 404; tanpa akses = 403.
Tampilkan pesan server apa adanya. Uang dan harga = angka desimal; jumlah stok maks 3 desimal.

--- 2.1 Bahan (daftar) ---
GET /cashier/outlets/{outletId}/fnb/inventory/items?status=OK|LOW|OUT|NEGATIVE (status opsional)
    data: [{ id, sku, name, unit, reorderLevel, active, quantityOnHand, stockStatus, averageUnitCost }]
    averageUnitCost HANYA terisi untuk OWNER; untuk CASHIER selalu null (field tidak dikirim/null).
    Rata-rata juga null bila belum pernah ada restock berharga. Saring active=false di UI stok opname.

--- 2.2 Restock dengan harga beli (OWNER dan CASHIER) ---
POST /cashier/outlets/{outletId}/fnb/inventory/items/{inventoryItemId}/movements   -> 201
     body { type: "RESTOCK"|"WASTE"|"ADJUSTMENT", quantity (maks 3 desimal; RESTOCK positif),
            unitCost? (>= 0, maks 4 desimal; HANYA untuk RESTOCK), reason? (maks 1000),
            idempotencyKey (wajib, maks 128) }
     unitCost pada tipe selain RESTOCK = 400 "Harga beli hanya untuk restock." (jangan kirim field ini untuk tipe lain).
     data: { id, inventoryItemId, type, quantityDelta, reason, orderId?, occurredAt, actorMembershipId, unitCost }
     unitCost di respons HANYA terisi untuk OWNER; CASHIER selalu null.
GET  /cashier/outlets/{outletId}/fnb/inventory/items/{inventoryItemId}/movements?page=0&size=20  (kolom unitCost sama, owner only)

--- 2.3 Stok opname batch (OWNER saja) ---
POST /merchant/outlets/{outletId}/fnb/inventory/stock-counts   -> 201 (juga saat replay)
     body { idempotencyKey (wajib, maks 128), note? (maks 500),
            lines: [{ inventoryItemId: uuid, countedQuantity: number (>= 0, maks 3 desimal) }] }  (1-200 baris)
     data: { lines: [{ inventoryItemId, name, unit, previousQuantity, countedQuantity, difference, movementId? }] }
     movementId null = selisih 0 (tidak ada mutasi). Selisih dicatat sebagai ADJUSTMENT "Stok opname[: note]".
     Error: 400 "Bahan duplikat dalam stok opname." ; 404 bahan tidak ada/nonaktif/outlet lain ;
            409 "Kunci idempotensi telah digunakan." (key sama, isi berbeda) ; 403 untuk CASHIER.
     Key sama + isi sama = hasil lama (aman di-retry).

--- 2.4 Laporan penjualan owner ---
GET /merchant/outlets/{outletId}/fnb/reports/sales?startDate=&endDate=&orderStatus=&paymentMethod=&page=&size=&topProducts=
    (startDate/endDate yyyy-MM-dd menurut zona waktu outlet, maks 366 hari; size maks 100; topProducts maks 50)
    data (field yang sudah ada: startDate, endDate, timeZone, transactions, paymentBreakdown, page, size, hasNext) plus:
    summary: { subtotal, netSales, total, amountCollected, transactionCount, averageTransaction, discountAmount,
               taxAmount, serviceChargeAmount, cancellationCount,
               costOfGoodsSold, grossProfit, itemsWithoutCost, refundCount, refundAmount }
       grossProfit = netSales (setelah diskon pesanan) - costOfGoodsSold.
       refundCount/refundAmount = refund yang terjadi di rentang tanggal (tanpa filter status/metode).
    topProducts: [{ productId, productName, quantity, grossSales, costOfGoodsSold, grossProfit, itemsWithoutCost }]
       grossProfit per menu = grossSales - costOfGoodsSold (sebelum diskon pesanan; jumlahnya bisa lebih besar
       dari laba kotor total sebesar diskon).
    hourly: [{ hour (0-23, zona outlet), transactionCount, netSales }]   (hanya jam yang ada data, urut naik)
    cashiers: [{ staffUserId, fullName, transactionCount, netSales, amountCollected }]  (netSales turun)
    shifts: [{ shiftId?, cashierName?, openedAt?, closedAt?, status?, transactionCount, netSales }]  (openedAt turun;
            pembayaran tanpa shift = satu baris dengan field shift kosong)
    Field null tidak dikirim. Pesanan lunas sebelum V9 dan item tanpa resep/harga rata-rata dihitung sebagai
    itemsWithoutCost (HPP-nya tidak ikut dijumlahkan, jadi grossProfit-nya terlalu tinggi).
GET .../fnb/reports/sales/csv tidak berubah.

==================================================================
3. FITUR YANG DIKERJAKAN
==================================================================
3.1 Restock dengan harga beli (kasir dan owner)
  - Form restock yang ada: tambah kolom opsional "Harga beli per satuan" (>= 0, maks 4 desimal, tampilkan satuan
    bahan). Kosong = jangan kirim unitCost. Tampilkan hanya pada tipe RESTOCK (sembunyikan/bersihkan untuk
    Waste dan Adjustment). Kasir boleh mengisi; kasir tidak melihat rata-rata atau unitCost apa pun.

3.2 Kolom harga rata-rata (owner saja)
  - Daftar bahan: kolom "HPP rata-rata" (averageUnitCost) dan, bila ada, "Nilai stok" TIDAK dihitung di UI
    (jangan kalikan sendiri). Tampil hanya bila peran OWNER; untuk kasir kolom tidak dirender. Nilai kosong
    ditampilkan sebagai "-" (belum ada harga beli). Riwayat mutasi: kolom "Harga" (unitCost) owner only.

3.3 Layar stok opname (owner)
  - Halaman/dialog baru di area inventori: daftar bahan AKTIF (dari 2.1) dengan stok sistem (quantityOnHand),
    input "Hitungan fisik" per baris (>= 0, maks 3 desimal), kolom selisih pratinjau (hitung - sistem) dengan
    warna kurang/lebih, kolom kosong dianggap tidak dihitung dan TIDAK dikirim.
  - Catatan opsional (maks 500). Tombol "Simpan opname" membuka ringkasan konfirmasi berisi hanya baris yang
    diisi beserta selisihnya (baris selisih 0 diberi label "sesuai"); kirim hanya setelah konfirmasi.
  - Idempotency key SEKALI per submission (useRef), dipakai ulang saat retry; buat baru setelah sukses atau saat
    pengguna mengubah isi lalu mengirim ulang. Tombol dinonaktifkan selama mutasi berjalan.
  - Setelah sukses tampilkan hasil dari respons (previousQuantity -> countedQuantity, difference) dan invalidate
    daftar bahan serta mutasi. Tangani 404/409/400 dengan pesan server (409: minta mulai ulang dengan key baru).
  - Opname tidak mengubah HPP rata-rata; jangan menyiratkan sebaliknya.

3.4 Laporan owner: tab dan kartu
  - Halaman laporan yang ada: tambah tab "Per jam", "Per kasir", "Per shift" (tabel/grafik sederhana dari
    hourly, cashiers, shifts). Jam ditampilkan sebagai 00:00-23:00 menurut zona waktu outlet; shift tanpa data
    shift diberi label "Tanpa shift"; openedAt/closedAt ditampilkan dengan zona waktu outlet (timeZone laporan).
  - Kartu ringkasan: tambah "HPP" (costOfGoodsSold) dan "Laba kotor" (grossProfit) di samping penjualan bersih;
    kartu "Refund" (refundCount + refundAmount). Jika itemsWithoutCost > 0 tampilkan petunjuk di kartu laba kotor:
    "N item tanpa HPP, laba kotor bisa lebih tinggi dari sebenarnya".
  - Tabel laba per menu (dari topProducts): kolom penjualan kotor, HPP, laba kotor; baris dengan
    itemsWithoutCost > 0 menampilkan keterangan "N item tanpa HPP". Beri catatan kecil bahwa laba per menu
    dihitung sebelum diskon pesanan.
  - Filter tanggal/status/metode yang ada tetap berlaku untuk semua bagian; refund mengikuti rentang tanggal saja.
  - Jangan menghitung ulang HPP atau laba di UI; tampilkan nilai server.

==================================================================
4. LAPORAN AKHIR
==================================================================
Tulis reports/m6-ui/REPORT.md: file yang diubah, simbol yang dipakai ulang (nama + path), hasil pencarian
duplikasi, hasil tsc/lint/build/test, fitur yang belum dikerjakan beserta alasan, dan risiko. Jangan menyatakan
sesuatu "terverifikasi" bila tidak dijalankan.
```
