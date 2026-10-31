# FastAPI, Telegram, dan percakapan persisten

Layanan memakai aiogram long polling saja, PostgreSQL AI tersendiri, dan satu worker
serial. Tidak ada akses data atau tindakan Cashier. Identitas berasal dari scope
server, ID bot, dan ID user Telegram; username boleh berubah atau hilang. Semua
perintah dan teks hanya menerima chat pribadi manusia serta allowlist opsional.

Entity tabel berada di `app/chat/entities.py`, memakai SQLAlchemy `DeclarativeBase`:

| Entity | Tabel |
| --- | --- |
| `TelegramIdentity` | `telegram_identities` |
| `ConversationSession` | `conversation_sessions` |
| `ConversationMessage` | `conversation_messages` |
| `TelegramInbox` | `telegram_inbox` |
| `TelegramOutbox` | `telegram_outbox` |

`app/chat/repositories.py` memakai query ekspresi SQLAlchemy atas pemetaan tersebut.
Worker memanggil operasi repository; transaksi tetap memakai koneksi pemilik bot.
Alembic menggunakan `Base.metadata`, sementara pembuatan tabel tetap melalui migrasi
`migrations/versions/0001_telegram.py` yang dijalankan operator.

1. Salin `.env.example` ke `.env` tanpa menimpa konfigurasi lokal. Isi
   `TELEGRAM_BOT_TOKEN`, `OPENROUTER_API_KEY`, `AI_MODEL`, `DATABASE_URL`, dan
   `TELEGRAM_SERVICE_SCOPE`. DATABASE_URL harus URL PostgreSQL database AI yang
   sudah disiapkan operator. Tidak ada fallback SQLite, memory, atau kredensial
   default. Scope adalah pemisah layanan yang ditentukan server, bukan bukti tenant.
2. `TELEGRAM_ALLOWED_USER_IDS` opsional, berisi ID positif dipisahkan koma. Nilai
   kosong membuka bot pribadi untuk semua manusia, memakai saldo provider operator.
   Environment mengungguli `.env`; restart setelah perubahan konfigurasi.
3. Dependency lock sudah diperbarui. Untuk menyiapkan environment dan database,
   jalankan:

   ```powershell
   uv sync --locked
   uv run alembic upgrade head
   uv run main.py
   ```

   Migrasi adalah langkah operator, tidak dijalankan aplikasi saat startup.
   F5 dengan konfigurasi Uvicorn yang tersedia juga boleh, satu proses tanpa reload
   atau beberapa worker. Setiap bot wajib memakai database AI yang sama pada seluruh
   deployment: advisory lock PostgreSQL tidak melindungi bot di dua database berbeda.
4. Kirim `/start`, lalu pertanyaan. `/start payload` tetap menyapa tanpa reset.
   `/session` menampilkan referensi sesi sendiri. `/new` menutup sesi lama dan
   membuka sesi baru; history tidak dihapus. Perintah lain menerima petunjuk aman.

`/` dan `/health` tetap `{"status":"ok"}` sebagai liveness HTTP. `/ready` memberi
503 jika DB, kepemilikan, intake, poller, supervisor, atau worker tidak siap. Readiness
bukan jaminan Telegram/provider sedang menerima koneksi; polling SDK melakukan
backoff saat Telegram tidak tersedia. Startup menolak webhook aktif, tidak menghapus
webhook atau pending updates. Operator menghapus webhook sendiri jika ingin polling.

## Durabilitas dan urutan

Intake melakukan commit profil dan inbox sebelum kembali ke dispatcher; model tidak
dipanggil di handler. Update duplikat tidak membuat job baru. Worker global memproses
sesuai urutan penerimaan, termasuk `/new`. Semua query operasional menggunakan scope
server dan bot, dengan identity/chat/session ketika diperlukan. Foreign key komposit
mencegah tautan lintas scope, bot, identity, dan chat. Satu koneksi khusus memegang
advisory lock bot dan melakukan transaksi singkat secara serial. Koneksi itu tidak
boleh reconnect otomatis setelah lock hilang; layanan berhenti dan perlu restart.

Koneksi asyncpg memiliki timeout connect 10 detik dan command 15 detik. NullPool
menutup sesi fisik saat owner connection ditutup, sehingga koneksi yang masih
memegang advisory lock tidak dikembalikan ke pool hidup. Engine dispose selalu
 dicoba melalui finally, termasuk jika connection close gagal. URL invalid dilaporkan
 dengan pesan konfigurasi aman tanpa URL atau kredensial.

DB transaction-abort/availability yang dapat diulang pada koneksi pemilik utuh ditunggu
oleh intake dengan backoff. Putusnya koneksi pemilik adalah kegagalan fatal, bukan
kesempatan mengambil lock baru sambil terus polling. Handler memberi sinyal supervisor
dan tetap tersuspensi pada kegagalan tak dikenal: aiogram tidak mendapat kesempatan
mengembalikan handler dan memajukan offset. Supervisor memakai `stop_polling` publik
untuk membatalkan dan menunggu child polling. Riwayat durabel hanya setelah commit;
Telegram menyimpan pending update maksimal 24 jam. Outage sebelum commit yang terlalu
lama tetap dapat kehilangan update.

USER disimpan sekali. History provider hanya pasangan sukses dari sesi sama, ditambah
USER sekarang sekali. Error dan hasil command tidak menjadi history model. Nama profil
masuk sebagai JSON user berlabel tidak terpercaya, tidak dimasukkan ke instruksi system.
Profil prompt dibatasi. Input maksimal 4000 karakter, output 1024 token, timeout 45 detik.
Prompt dibatasi konservatif 20000 byte UTF-8 termasuk overhead, maksimal 32 pasangan
kandidat. Pilih model dengan context window minimal 32768 token, tokenizer yang tidak
melebihi satu token per byte untuk teks UTF-8, dan overhead chat di bawah 4096 token.
Anggaran ini sengaja konservatif, bukan penghitungan tokenizer model secara tepat.
Model/tokenizer di luar asumsi ini perlu penyesuaian anggaran sebelum dipakai.

Provider 429 dan HTTP 500/502/503/504 mendapat maksimal tiga attempts, dengan backoff.
401/402/403, format invalid, dan kegagalan transport/timeout ambigu tidak diulang otomatis.
HTTP 5xx dapat tetap menimbulkan biaya provider berulang; tidak ada exactly-once provider.
Crash pada PROCESSING dipulihkan sebagai error AI_CRASH_UNKNOWN tanpa regenerasi:
USER yang sudah tersimpan dipertahankan dan outbox ERROR dibuat. User harus mengirim
pertanyaan baru secara eksplisit jika ingin mencoba lagi. ASSISTANT, ID/model respons,
outbox, dan hasil turn disimpan dalam satu transaksi. Gagal mengirim tidak memanggil
model ulang. History hanya memasukkan pasangan yang seluruh delivery-nya selesai sukses.

Chunk dikirim berurutan, maksimal 4000 unit UTF-16. Telegram 429 menyimpan retry_after,
maksimal lima attempts; explicit server error maksimal tiga attempts. Retry pengiriman
menahan seluruh antrean serial sampai jatuh tempo atau terminal; user lain dapat tertunda.
Putus koneksi/timeout send atau crash SENDING menjadi UNKNOWN dan tidak dikirim ulang
otomatis. Semua chunk berikutnya dari respons itu ditutup FAILED agar tidak muncul ekor
tanpa kepala. UNKNOWN dapat berarti pesan sudah sampai, tetapi acknowledgment hilang.
Tidak ada exactly-once sendMessage atau fitur resend operator pada tahap ini.

Shutdown menunggu observer startup publik atau selesainya runner sebelum meminta
stop. Observer berjalan setelah running lock dan stop signals tersedia. Runner yang
sudah mulai tidak dibatalkan oleh aplikasi: satu graceful-stop task dilindungi dari
cancellation pemanggil dan menunggu teardown child native aiogram, tanpa fallback
cancel setelah 10 detik. Ini bukan batas total shutdown 10 detik; DB I/O dibatasi oleh
timeout asyncpg. Setelah poller selesai, worker diberi grace 15 detik, lalu cancel dan
await jika diperlukan. Worker yang sudah cancelled atau gagal tetap diambil hasilnya;
finally memastikan penutupan bot dan DB. Stop berulang memakai cleanup task yang sama.
HTTP client ditutup oleh lifespan setelah cleanup channel selesai.

Recovery mengikuti status durabel: PENDING/RETRY/READY dapat dilanjutkan sesuai antrean;
PROCESSING menghasilkan error tanpa regenerasi; SENDING menjadi UNKNOWN tanpa resend.
Isi percakapan dan profil disimpan untuk history, tidak dicetak ke log. SDK logging
ditekan untuk mencegah URL/token exception bocor. Retensi/penghapusan data perlu
keputusan operator sebelum produksi; `/new` bukan penghapusan data.

## Verifikasi berikutnya

Kode sudah diterapkan dan dependency lock tersedia. Pemeriksaan sintaks dan type hint
statis berhasil. Sebanyak 74 unit test offline lulus sebelum refactor query SQLAlchemy.
Test pada source setelah refactor belum dijalankan; aplikasi, migrasi database,
Telegram, dan provider nyata juga belum dijalankan.
Setelah konfigurasi dan migrasi database disiapkan, minta testing eksplisit:
isolasi dua user bernama sama; username
berubah/hilang; restart history; `/new` di antara dua pesan; allowlist semua perintah;
duplicate update; DB abort dan owner disconnect tanpa offset maju; proses kedua ditolak;
webhook aktif; provider retry/timeout/crash; recovery outbox; astral Unicode chunks;
429 retry_after; UNKNOWN menghentikan tail; URL invalid dan port malformed tanpa
credential leakage; connection close failure dengan dispose; startup cancellation
sebelum/sesudah observer; supervisor cancellation saat graceful stop; worker sudah
cancelled; stop berulang; shutdown dengan I/O tertunda; readiness 503 saat child mati.
Jangan mengklaim runtime acceptance berdasarkan review source atau agent report.

## Menjalankan unit test

Suite menggunakan `unittest` bawaan Python dengan mock untuk database dan transport;
tidak memerlukan kredensial atau konfigurasi `.env`. Dari direktori Ai-services:

```powershell
uv run --locked python -m unittest discover -s tests/unit -p "test_*.py" -v
```

Delapan modul memeriksa konfigurasi, prompt/OpenRouter, intake/sender Telegram,
repository, conversation worker, ownership database, serta lifecycle polling.
Test lifecycle menjalankan start/stop Dispatcher asli dengan child polling jaringan
diganti mock. Unit test memeriksa query/parameter dan batas transaksi; constraint,
durabilitas, dan concurrency PostgreSQL nyata memerlukan integration test terpisah.
Hasil eksekusi: [catatan unit test](docs/progress/telegram-unit-review.md).
