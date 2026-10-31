# AI Customer Service

Status: foundation LangGraph/OpenRouter selesai (TASK-038), FastAPI dan adapter Telegram lokal sementara telah direview source (TASK-040), **READY FOR TESTING**. Kredensial perlu diisi lokal; aplikasi dan bot belum dijalankan atau diuji. WhatsApp Business ditunda; web chat terautentikasi, antrean durable, Spring/gRPC, order, ticket, dan database belum diimplementasikan.

Python 3.12, FastAPI untuk health dan adapter Telegram lokal, serta LangGraph/LangChain untuk AI foundation. WebSocket, gRPC Spring, dan Meta WhatsApp hanya arah rencana yang belum dihubungkan. Baca [PLAN.md](PLAN.md) untuk responsibilities dan urutan delegation.

## Setup environment

Jalankan dari `E:\Java\cashier-saas\Ai-services`:

```powershell
uv sync --locked
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Perintah kedua tidak menimpa `.env` yang telah berisi kredensial. Provider aktif adalah OpenRouter melalui `langchain-openrouter` dan `ChatOpenRouter`. Isi `AI_MODEL` dengan slug `provider/model` yang mendukung tool calling dan `OPENROUTER_API_KEY` pada environment atau `.env`; `.env` di-ignore Git. Tidak ada model berbayar yang dipilih otomatis atau API call dilakukan saat setup.

Foundation tersedia melalui `app.config.load_settings()`, `app.ai.agent.build_agent(settings, faq_service)`, dan `await agent.respond(context, message, history)`. Pemanggil harus menyediakan konteks yang sudah terotorisasi dan FAQ yang disetujui per tenant. Foundation tidak melakukan autentikasi, persistence, streaming, atau delivery. Laporan kontrak: `reports/TASK-038.md`; review integrasi: `reports/TASK-039.md`.

## Telegram lokal sementara

Adapter ini hanya long polling lokal untuk satu bot dan satu proses. Bot menerima pesan teks private dari ID pengguna yang ditulis eksplisit pada allowlist. `TELEGRAM_TENANT_ID` adalah scope bisnis tepercaya yang dikonfigurasi operator, bukan identitas atau autentikasi pelanggan. Tidak ada endpoint publik untuk mengirim pesan, akses pesanan/ticket, atau pilihan tenant/user/chat/penerima oleh model.

1. Buat bot melalui [BotFather](https://core.telegram.org/bots/features#botfather): buka chat BotFather, kirim `/newbot`, lalu simpan token hanya di `.env` lokal. Jangan taruh token di chat, screenshot, Git, atau FAQ.
2. Cari numeric user ID tanpa bot pihak ketiga: kirim `/start` atau pesan biasa ke bot baru. Lalu jalankan PowerShell berikut secara lokal. Token diminta tersembunyi, tidak ditulis ke history, URL, atau output; perintah hanya menampilkan ID pengirim unik. Jika gagal, ia menampilkan pesan generik tanpa body error.

```powershell
$secureToken = Read-Host 'Telegram bot token' -AsSecureString
$tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try {
  $telegramToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
  $updates = Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$telegramToken/getUpdates" -ErrorAction Stop
  $updates.result | ForEach-Object { $_.message.from.id } | Where-Object { $_ } | Sort-Object -Unique
} catch {
  Write-Host 'getUpdates gagal. Periksa koneksi, token lokal, atau pesan ke bot.'
} finally {
  if ($tokenPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer) }
  Remove-Variable telegramToken -ErrorAction SilentlyContinue
}
```

Telegram tidak menyediakan endpoint “my user id” tersendiri. Jangan membagikan output update selain ID yang diperlukan untuk allowlist.

3. Isi `.env` dengan `TELEGRAM_ENABLED=true`, token, tenant lokal, serta JSON allowlist, misalnya `TELEGRAM_ALLOWED_USER_IDS=[123456789]`. Isi model/key OpenRouter yang juga diperlukan oleh foundation AI.
4. Opsional, buat JSON FAQ yang hanya berisi fakta yang sudah disetujui, lalu isi `TELEGRAM_FAQ_FILE` dengan path file itu. Bentuknya:

```json
{
  "tenant-demo": [
    {"question": "Jam buka?", "answer": "Isi hanya jawaban yang disetujui bisnis."}
  ]
}
```

Jika file FAQ kosong/tidak dipasang, bot tidak membuat fakta bisnis dan akan meminta klarifikasi atau menawarkan eskalasi. Jalankan satu proses saja, tanpa reload:

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8001 --workers 1
```

`/start` dan `/help` menampilkan petunjuk lokal singkat; `/reset` menghapus riwayat volatile hanya untuk sender/chat tersebut. Jangan gunakan `--reload` untuk polling. Health lokal tersedia pada `http://127.0.0.1:8001/health/live` dan `http://127.0.0.1:8001/health/ready`; readiness Telegram gagal memakai HTTP 503 dengan pesan tersanitasi dan tidak mengungkap token, tenant, ID pengguna, model, atau detail error.

Saat lifecycle mulai, adapter memeriksa apakah webhook Telegram sudah ada dan berhenti dengan readiness gagal bila ada; ia tidak pernah menghapus webhook, mengatur webhook, atau membuang pending update. Offset polling dan riwayat chat hanya di memori: restart/crash dapat mengulang update atau kehilangan konteks. Bila send hasilnya ambigu, poller berhenti tanpa retry otomatis; restart operator masih dapat menyebabkan duplikat. Hanya HTTP 429 dengan `retry_after` valid yang menunggu durasi Telegram lalu mencoba kembali; delay terlalu besar atau respons lain menghentikan poller. Aplikasi tidak mengklaim exactly-once. Telegram menyimpan update hingga 24 jam, sehingga operator tetap harus menjalankan hanya satu instance. Rencana Redis durable queue masih ditunda.

Tanggung jawab sederhana: `app/main.py` mengelola lifecycle HTTP/poller, `app/telegram/client.py` berkomunikasi hanya dengan API Telegram resmi, `service.py` memeriksa allowlist dan riwayat volatile, `polling.py` memproses berurutan dan baru acknowledge setelah reject aman atau delivery berhasil, dan `faq.py` memuat FAQ JSON lokal yang tervalidasi.

Referensi resmi: [getUpdates](https://core.telegram.org/bots/api#getupdates), [BotFather](https://core.telegram.org/bots/features#botfather), dan [FastAPI lifespan](https://fastapi.tiangolo.com/advanced/events/).

Root Cashier tetap Java17/Boot4.1.1 dan PostgreSQL. AI service tidak mengakses ORM/database bisnis secara langsung. Tidak ada pendaftaran bot, startup, test aplikasi, Maven, provider/API Telegram, atau database command yang dijalankan saat implementasi ini.
