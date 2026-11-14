# AI Services — Project Progress & Context Memory

## 1. Overview
Layanan ini merupakan subsistem AI Customer Service untuk ekosistem **Cashier SaaS** (multi-tenant POS & F&B). Layanan mengintegrasikan antarmuka bot perpesanan (Telegram long polling, WhatsApp terencana) dengan LLM via OpenRouter API, serta penyimpanan sesi & riwayat chat di PostgreSQL terisolasi per tenant.

## 2. Tech Stack & Architecture
- **Language & Package Manager**: Python 3.12, dikelola menggunakan `uv`.
- **HTTP & Lifecycle**: FastAPI (`/` dan `/health` untuk liveness, `/ready` untuk kesiapan bot/DB).
- **Bot Engine**: `aiogram` 3.x (single-process long polling, supervised task, graceful child teardown).
- **LLM Provider**: `OpenRouterClient` (`aiohttp`, fallback/retry konservatif, UTF-16 chunked delivery).
- **Persistence**: SQLAlchemy 2.0 (async), `asyncpg`, migrasi via Alembic.
- **Tool Calling System**: `app/tools` (`BaseTool`, `ToolRegistry`, `CatalogSearchTool`, `OrderStatusTool`), diintegrasikan otomatis pada `ChatService` & bot Telegram.
- **Safety / Lock**: PostgreSQL session-level advisory lock (`BOT_OWNERSHIP_KEY`) untuk menjamin kepemilikan tunggal bot.

## 3. Database Schema (Minimal Multichannel)
Menggunakan revisi skema `0001_channels` (6 tabel, 31 kolom), skema minimal hasil simplifikasi tanpa antrean durabel:
1. `tenants`: Registry tenant AI (`id` UUID).
2. `channel_accounts`: Akun/bot per platform (`TELEGRAM`, `WHATSAPP`).
3. `channel_users`: Identitas user per platform (terisolasi, tanpa cross-link).
4. `tenant_memberships`: Keanggotaan terverifikasi user ke tenant.
5. `conversations`: Sesi percakapan (`/new` menutup sesi lama dan membuat sesi baru).
6. `messages`: Riwayat pesan USER & ASSISTANT dengan ID provider eksternal.

## 4. Invariants & Alur Pemrosesan Pesan
- **Alur Serial ChatService**: Validasi intake (private chat manusia & allowlist opsional) -> Simpan USER dalam transaksi singkat -> Panggil LLM di luar transaksi -> Simpan ASSISTANT -> Kirim respons berurutan.
- **Isolasi Tenant**: Diikat melalui `channel_accounts.tenant_id` (`AI_TENANT_ID`), bukan dari klaim pesan.
- **Budgeting & Batasan**:
  - Input maksimal 4.000 karakter, context prompt maksimal 20.000 byte UTF-8 (~32 pasangan riwayat).
  - Output token maksimal 1.024 token, timeout 45 detik, maksimal 3 kali retry jika retryable.
  - Chunk pesan Telegram maksimal 4.000 unit UTF-16.
  - Log error disanitasi; token, pesan, dan kredensial dilarang dicatat di log.

## 5. Status Progress & Verifikasi
- **Status Terkini**: `SOURCE REVIEWED / READY FOR TESTING` (per 13 Oktober 2026).
- **Unit Suite**: 8 modul unit test (`tests/unit/`) telah disesuaikan dengan arsitektur skema minimal.
- **Kebijakan Testing**: Test tidak dijalankan secara otomatis saat penulisan kode; hanya dijalankan atas instruksi eksplisit dari pengguna.

## 6. Perintah Operasional
```powershell
# 1. Sinkronisasi dependensi & upgrade skema database AI
uv sync --locked
uv run alembic upgrade head

# 2. Menjalankan AI Service lokal
uv run main.py

# 3. Menjalankan unit test (hanya jika diminta eksplisit)
uv run --locked python -m unittest discover -s tests/unit -p "test_*.py" -v
```

## 7. Aturan Eksekusi Terminal & Guardrail Agent (Command Safety)
Untuk meminimalisir bug dan mencegah eksekusi terminal secara sembarangan, seluruh Agent/Worker yang beroperasi di repositori ini WAJIB mematuhi guardrail berikut:
- **DILARANG Perintah Destruktif**:
  - Dilarang menjalankan penghapusan massal atau rekursif (`rm -rf`, `del /s /q`, `Remove-Item -Recurse`).
  - Dilarang perintah Git yang mengubah riwayat secara destruktif (`git reset --hard`, `git push --force`, `git clean -fd`).
  - Dilarang mengunduh dan mengeksekusi binary atau script eksternal secara liar (`curl ... | sh`, `wget`, script tak terverifikasi).
  - Dilarang menjatuhkan database atau tabel (`DROP DATABASE`, `DROP TABLE`).
- **HANYA Izinkan Perintah Resmi Proyek**:
  - Manajemen dependensi dan runtime Python via `uv` (`uv sync`, `uv lock`, `uv run main.py`).
  - Kompilasi protobuf resmi: `uv run python -m grpc_tools.protoc ...`.
  - Automated testing lokal: `uv run python -m unittest ...` (hanya jika diminta eksplisit oleh pengguna).
  - Inspeksi Git *read-only*: `git status`, `git diff`.
- **Inspeksi Kode**: Selalu prioritaskan `codebase-memory-mcp` dan tool `read_file` bawaan daripada menjalankan command shell arbitrer untuk membaca isi berkas.

