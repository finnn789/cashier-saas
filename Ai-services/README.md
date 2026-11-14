# AI Customer Service — Cashier SaaS

Layanan ini merupakan subsistem **AI Customer Service** berbasis kecerdasan buatan (*conversational agent*) untuk ekosistem **Cashier SaaS** (aplikasi POS & F&B multi-tenant). Layanan bertugas menghubungkan bot perpesanan pelanggan (saat ini aktif dengan **Telegram**, ke depan mendukung WhatsApp) dengan model LLM via **OpenRouter API**, serta menyimpan percakapan dan sesi chat secara terisolasi di database PostgreSQL tersendiri.

Arsitektur percakapan dikelola menggunakan alur kerja berbasis graf terstruktur (**LangGraph StateGraph**), menjamin kontrol penuh terhadap ekosistem multi-turn tool calling, penanganan kegagalan yang tangguh, transaksi database relasional yang singkat (*short database transactions*), serta kepatuhan mutlak terhadap isolasi tenant (*tenant isolation*).

---

## 1. Tech Stack yang Digunakan

| Kategori | Teknologi | Deskripsi Penggunaan |
| :--- | :--- | :--- |
| **Language & Environment** | **Python 3.12** | Runtime utama, dikelola menggunakan package manager modern **`uv`** yang cepat dan ter-lock (`uv.lock`). |
| **Conversational Agent Engine** | **LangGraph & LangChain Core** | Orkestrasi workflow percakapan berbasis graph stateful ([`app/ai/graph/`](app/ai/graph/)) menggunakan `StateGraph`, reducer `add_messages`, adapter `StructuredTool`, dan conditional router (`should_continue`). |
| **Web Framework & HTTP** | **FastAPI** & **Uvicorn** | Menyediakan liveness & readiness probes HTTP (`/`, `/health`, `/ready`), serta mengelola lifecycle aplikasi via asynchronous context manager (`lifespan`). |
| **Messaging Engine** | **aiogram 3.x** | Integrasi bot Telegram menggunakan pola *single-process long polling* yang disupervisi dengan mekanisme *graceful teardown* untuk child process/task. |
| **AI Provider** | **OpenRouter API** via **ChatOpenAI / aiohttp** | Klien LLM asinkron ([`app/ai/graph/nodes.py`](app/ai/graph/nodes.py), [`app/ai/openrouter.py`](app/ai/openrouter.py)) untuk berinteraksi dengan model-model LLM terdepan (Claude 3.5, Gemini 2.5 Flash, Llama 3, GPT-4o, dll.) dengan chunking pesan UTF-16 dan error guard. |
| **RPC & Inter-Service Integration** | **gRPC (`grpc.aio`)** & **Protobuf** | Klien RPC asinkron mandiri ([`app/integrations/grpc/client.py`](app/integrations/grpc/client.py)) untuk integrasi data menu & pesanan dari backend Cashier SaaS dengan isolasi tenant dan fallback toleran offline. |
| **Database & ORM** | **SQLAlchemy 2.0 (Async)** & **asyncpg** | Interaksi database PostgreSQL asinkron dengan pola Declarative Base dan ekspresi query murni ([`app/chat/entities.py`](app/chat/entities.py), [`app/chat/repositories.py`](app/chat/repositories.py)). |
| **Database Migration** | **Alembic** | Versioning dan migrasi skema database relasional (revisi aktif: `0001_channels`). |
| **Tool Calling System** | **Native Tool Registry & Adapter** | Subsistem pemanggilan tool ([`app/tools`](app/tools)) diadaptasi ke LangChain `StructuredTool` ([`app/ai/graph/tools_adapter.py`](app/ai/graph/tools_adapter.py)) dengan penyembunyian skema `tenant_id` dari LLM dan injeksi server-side context runtime yang aman. |
| **Process Concurrency Safety**| **PostgreSQL Advisory Lock** | Session-level advisory lock (`BOT_OWNERSHIP_KEY`) untuk menjamin kepemilikan tunggal bot agar tidak terjadi *split-brain polling* jika ada deployment ganda. |

---

## 2. Penjelasan Proyek & Arsitektur

### 2.1. Skema Database Minimal Multichannel (`0001_channels`)
Database AI dirancang terpisah dari database utama Java Cashier SaaS untuk isolasi beban kerja. Menggunakan skema minimal hasil simplifikasi: **6 tabel, 31 kolom** tanpa antrean durabel:

1. **`tenants`** (2 kolom): Registry tenant AI dengan identifier `UUID`.
2. **`channel_accounts`** (5 kolom): Akun bot per platform (`TELEGRAM` atau `WHATSAPP`) yang terikat ke satu `tenant_id`.
3. **`channel_users`** (6 kolom): Identitas pengguna per platform (terisolasi per platform, tanpa cross-link otomatis).
4. **`tenant_memberships`** (4 kolom): Keanggotaan terverifikasi pengguna terhadap tenant tertentu.
5. **`conversations`** (8 kolom): Sesi percakapan aktif/tertutup. Perintah `/new` menutup sesi lama dan membuat sesi baru.
6. **`messages`** (6 kolom): Riwayat pesan masuk (`USER`) dan balasan (`ASSISTANT`) dengan `BIGINT` ID dan provider message ID unik.

*Detail field dan relasi lengkap dapat dilihat di [docs/channel-schema.md](docs/channel-schema.md).*

### 2.2. Alur Pemrosesan Pesan (*Chat Pipeline*) & Transaksi Database Singkat
Layanan menggunakan [`ChatService`](app/chat/service.py) serial dengan transaksi database singkat terpisah:
1. **Intake Validation**: Memvalidasi chat private manusia, format input (1–4000 karakter), dan opsi allowlist ID pengguna.
2. **Transaksi Database 1 (Simpan Pesan Pengguna)**:
   - Menyimpan entitas pengguna (`upsert_user`).
   - Memeriksa duplikasi pesan eksternal (`find_duplicate_incoming`).
   - Menyimpan pesan `USER` ke tabel `messages`.
   - Mengambil riwayat percakapan yang telah selesai (`completed_history`).
   - **Komitmen Transaksi 1**: Koneksi DB segera dilepaskan sebelum invoking model AI atau eksekusi tools.
3. **Eksekusi LangGraph Workflow (Di Luar Transaksi DB)**:
   - Riwayat dikonversi menjadi urutan `HumanMessage` dan `AIMessage`.
   - Workflow graf dieksekusi via `await self.graph.ainvoke({"messages": formatted_messages, "tenant_id": str(tenant_id), ...})`.
   - Node `agent` memanggil LLM melalui OpenRouter API endpoint.
   - Jika model meminta tool, conditional edge `should_continue` merutekan ke node `tools` untuk eksekusi fungsi aman dengan konteks tenant terisolasi, lalu berputar kembali ke `agent`.
4. **Transaksi Database 2 (Simpan Balasan Asisten)**:
   - Respons final diekstrak dari `final_response` atau pesan `AIMessage` terakhir.
   - Pesan balasan disimpan ke tabel `messages` dengan role `ASSISTANT`.
   - **Komitmen Transaksi 2**: Selesai disimpan.
5. **Kirim Balasan**: Jawaban final dikirimkan ke Telegram secara terpotong berurutan (maksimal 4.000 unit UTF-16 per chunk).

### 2.3. Topologi Arsitektur LangGraph Workflow ([`app/ai/graph/`](app/ai/graph/))

```mermaid
flowchart TD
    START([Pesan Masuk / Initial State]) --> AgentNode["Node: agent (ChatOpenAI via OpenRouter)"]
    AgentNode --> ShouldContinue{"Edge: should_continue"}
    
    ShouldContinue -- "Ada Tool Calls" --> ToolsNode["Node: tools (Dispatch with injected tenant_id)"]
    ShouldContinue -- "Selesai / Jawaban Akhir" --> END([END / Selesai])
    
    ToolsNode --> AgentNode
```

- **`AgentState`** ([`app/ai/graph/state.py`](app/ai/graph/state.py)):
  TypedDict dengan reducer `add_messages` untuk pesan dinamis, serta field kontekstual terisolasi (`tenant_id`, `user_id`, `conversation_id`, `display_name`, `final_response`, `error_code`).
- **`tools_adapter`** ([`app/ai/graph/tools_adapter.py`](app/ai/graph/tools_adapter.py)):
  Menghilangkan parameter `tenant_id` dari skema JSON yang dipaparkan ke LLM, serta menginjeksi `tenant_id` runtime dari context server-side untuk menjamin keamanan antar tenant.
- **`nodes.py`** & **`edges.py`**:
  Node `agent` mengeksekusi model, node `tools` mengeksekusi registry tools, dan edge `should_continue` menentukan apakah model memerlukan eksekusi tool lanjutan atau selesai.
- **`workflow.py`**:
  Menyediakan fungsi kompilasi `build_chat_graph(openrouter_key, ai_model, tools=None)` yang mengembalikan `CompiledStateGraph`.

### 2.4a. Sinkronisasi Staf & Verifikasi Identitas Telegram (*dalam pengembangan*)
Status: belum terverifikasi end-to-end. Kontrak lengkap ada di [docs/contracts/member-sync.md](../docs/contracts/member-sync.md).
- **Sinkronisasi dorong (*push*) dari backend**: saat profil owner selesai dilengkapi dan saat undangan kasir diklaim, backend mengirim data staf (nama tenant, nama lengkap, nomor telepon ternormalisasi, role, status) ke endpoint internal `PUT /api/v1/internal/tenants/{tenant_id}/members/{java_user_id}`, diautentikasi dengan `AI_INTERNAL_API_KEY`.
- **Verifikasi lewat kontak Telegram**: pengguna hanya dikenali jika membagikan kontak Telegram miliknya sendiri (`contact.user_id` harus sama dengan pengirim). Nomor yang diketik manual diabaikan, kontak milik orang lain atau yang diteruskan ditolak. Nomor dicocokkan dengan staf `ACTIVE`: tidak ada yang cocok ditolak, satu kecocokan membuat keanggotaan terverifikasi, lebih dari satu membuat pengguna memilih tenant (`/tenant` untuk berganti).
- **Tenant per pesan**: untuk pengguna terverifikasi, tenant dan role diambil dari keanggotaan terverifikasi pada setiap pesan. Staf yang dinonaktifkan kehilangan akses.
- **`AI_TENANT_ID`** hanya fallback pengembangan dan tidak pernah menggantikan tenant dari staf terverifikasi.

### 2.4. Subsistem Tool Calling ([`app/tools`](app/tools))
- **[`BaseTool`](app/tools/base.py)**: Interface kelas dasar untuk mendefinisikan nama, deskripsi, parameter JSON Schema, dan method async `execute(*, tenant_id, **kwargs)`.
- **[`ToolRegistry`](app/tools/registry.py)**: Registry untuk mendaftarkan dan mengeksekusi tool lokal.
- **Built-in Tools**:
  - [`CatalogSearchTool`](app/tools/catalog.py) (`search_catalog_menu`): Pencarian menu dan ketersediaan stok produk kasir via gRPC (`GetCatalogItem`).
  - [`OrderStatusTool`](app/tools/order.py) (`check_order_status`): Pengecekan status dan rincian pesanan kasir berdasarkan nomor order via gRPC (`GetOrderStatus`).

---

## 3. Panduan Menjalankan Proyek

### 3.1. Prasyarat Sistem
- **Python 3.12+** terpasang.
- Package manager **`uv`** terpasang (`pip install uv` atau via installer mandiri).
- Server **PostgreSQL** aktif untuk database AI.

### 3.2. Konfigurasi Lingkungan (`.env`)
Salin file template `.env.example` menjadi `.env`:
```powershell
cp .env.example .env
```

Buka file `.env` dan lengkapi variabel berikut:
```ini
# Kredensial Bot Telegram
TELEGRAM_BOT_TOKEN="123456789:ABCdefGHIjklMNOpqrsTUVwxyz"

# Fallback tenant pengembangan (opsional, dalam pengembangan). Tidak menggantikan tenant dari staf yang sudah terverifikasi.
AI_TENANT_ID="00000000-0000-0000-0000-000000000001" # UUID tenant valid

# Konfigurasi LLM OpenRouter
OPENROUTER_API_KEY="sk-or-v1-xxxxxxxxxxxxxxxxxxxx"
AI_MODEL="anthropic/claude-3.5-sonnet" # atau google/gemini-2.5-flash, openai/gpt-4o-mini

# Database PostgreSQL AI
DATABASE_URL="postgresql+asyncpg://postgres:password@localhost:5432/cashier_ai"

# Opsional: Allowlist ID Telegram pengguna (kosongkan untuk menerima semua pengguna private)
TELEGRAM_ALLOWED_USER_IDS=""

# Konfigurasi Integrasi Backend Kasir via gRPC (Opsional, memiliki nilai default)
CASHIER_GRPC_HOST="127.0.0.1"
CASHIER_GRPC_PORT=9090
CASHIER_GRPC_TIMEOUT_SECONDS=5.0
CASHIER_GRPC_USE_TLS=false
# Wajib: harus sama dengan CASHIER_GRPC_SERVICE_KEY di backend (minimal 16 karakter)
CASHIER_GRPC_SERVICE_KEY="isi-dengan-secret-acak-minimal-16-karakter"

# Wajib untuk sinkronisasi staf (dalam pengembangan): harus sama dengan AI_INTERNAL_API_KEY di backend,
# dan berbeda dari CASHIER_GRPC_SERVICE_KEY. Dikirim sebagai header X-Internal-Service-Key.
AI_INTERNAL_API_KEY="isi-dengan-secret-acak-berbeda-minimal-16-karakter"
```

### 3.3. Instalasi Dependensi & Migrasi Database
Jalankan sinkronisasi dependensi dan terapkan migrasi skema tabel ke database:

```powershell
# 1. Sinkronisasi dependensi yang terkunci
uv sync --locked

# 2. Jalankan migrasi Alembic ke head revisi 0001_channels
uv run alembic upgrade head
```

### 3.4. Kompilasi Kontrak Protobuf (Opsional / Saat Pembaruan Skema)
Jika terdapat perubahan skema pada file `proto/cashier_service.proto`, kompilasi ulang stubs Python menggunakan perintah di bawah. Sumber kontrak ada di `../src/main/proto/cashier_service.proto`; salinan `proto/cashier_service.proto` harus identik dengan file tersebut (salin dulu sebelum kompilasi):
```powershell
uv run python -m grpc_tools.protoc -Iproto --python_out=app/integrations/grpc/generated --grpc_python_out=app/integrations/grpc/generated proto/cashier_service.proto
```

### 3.5. Menjalankan Layanan (Local Server)
Jalankan aplikasi utama:

```powershell
uv run main.py
```
Aplikasi akan menjalankan server HTTP Uvicorn pada `http://127.0.0.1:8001` sekaligus mengaktifkan long-polling bot Telegram di latar belakang.

### 3.6. Logging
Log berformat JSON satu objek per baris ke stdout (`ts`, `level`, `logger`, `message`, field korelasi seperti
`update_id`/`chat_id`/`tenant_id`, serta `event` dan field tambahan lain). Paket: [`app/observability`](app/observability).

| Variabel | Default | Keterangan |
| --- | --- | --- |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARNING`, `ERROR`, `CRITICAL`; nilai tidak valid menjadi `INFO` |
| `LOG_FORMAT` | `json` | `json` atau `text` (hanya untuk stdout; file selalu JSON) |
| `LOG_FILE_ENABLED` | `false` | `true` menulis juga ke `LOG_DIR/ai-services.log` (rotasi, ditulis lewat antrean agar event loop tidak terblokir) |
| `LOG_DIR` | `logs` | Relatif terhadap direktori kerja (`/app/logs` di Docker); sudah ada di `.gitignore` |
| `LOG_MAX_BYTES` / `LOG_BACKUP_COUNT` | `10485760` / `5` | Batas ukuran per file dan jumlah file cadangan |

Privasi: log tidak boleh berisi nomor telepon, nama, username, isi pesan/balasan, prompt, argumen/hasil tool, token,
kunci API, atau pesan exception dari SDK/DB. Hanya ID, jumlah/panjang, status, durasi, dan nama tipe exception.
`RedactionFilter` menutupi token bot Telegram, `Bearer ...`, kunci `sk-...`, dan deretan 9+ digit sebagai lapisan
pengaman tambahan, bukan pengganti aturan di atas.

Membaca log container: `docker logs <container> | jq 'select(.chat_id=="71")'` atau
`docker logs <container> | jq -r '[.ts,.level,.event,.message]|@tsv'`.

---

## 4. Verifikasi & Penggunaan

### 4.1. Endpoint Pemantauan HTTP
- **`GET /`** atau **`GET /health`**: Pemeriksaan liveness HTTP (mengembalikan `{"status": "ok"}`).
- **`GET /ready`**: Pemeriksaan kesiapan bot, supervisor, koneksi DB, dan advisory lock kepemilikan bot (mengembalikan status 200 `{"status": "ready"}` atau 503 jika komponen belum siap).

### 4.2. Berinteraksi Melalui Telegram
Buka bot Telegram Anda dan coba perintah/pertanyaan berikut.

*Dalam pengembangan:* pengguna staf perlu membagikan kontak Telegram miliknya (tombol *Bagikan kontak*) sebelum dapat menggunakan layanan. Nomor telepon yang diketik tidak diproses.

- **`/start`**: Menginisialisasi sesi dan menampilkan instruksi awal.
- **`/session`**: Menampilkan ID sesi percakapan aktif Anda.
- **`/new`**: Menutup sesi chat lama dan membuka sesi percakapan baru tanpa menghapus data history lama.
- **Pencarian Katalog Menu**: *"Bisa pesan menu apa saja?"* atau *"Ada kopi susu?"* (AI memanggil `search_catalog_menu` via gRPC).
- **Pengecekan Status Order**: *"Cek status pesanan ORD-20261022-001"* (AI memanggil `check_order_status` via gRPC).

---

## 5. Pengujian Unit (*Unit Tests*)

Sesuai dengan kebijakan repositori, unit test **tidak dijalankan secara otomatis** saat penulisan kode, melainkan dijalankan saat diminta secara eksplisit oleh pengguna.

Tersedia modul unit test di dalam folder [`tests/unit/`](tests/unit/):
```powershell
# Menjalankan pengujian khusus LangGraph Chat Service
uv run python -m unittest tests/unit/test_langgraph_chat.py -v

# Menjalankan pengujian khusus gRPC tools
uv run python -m unittest tests/unit/test_grpc_tools.py -v

# Menjalankan seluruh rangkaian unit test
uv run --locked python -m unittest discover -s tests/unit -p "test_*.py" -v
```

Cakupan pengujian mencakup:
- `test_langgraph_chat.py`: Alur kerja LangGraph end-to-end, transaksi ganda terisolasi (USER di Tx 1, ASSISTANT di Tx 2), dispatch tool terisolasi per tenant, penanganan error/timeout model, serta preservasi command.
- `test_grpc_tools.py`: Klien gRPC asinkron, penanganan fallback offline backend kasir, eksekusi `CatalogSearchTool`, `OrderStatusTool`, dan registrasi suite default.
- `test_tools.py`: Skema OpenRouter tool, registry dispatching, dan eksekusi tool.
- `test_ai.py`: Validasi prompt, budgeting token/karakter, dan OpenRouter provider mocking.
- `test_conversation.py`: Alur kerja `ChatService` dan batasan transaksi database legacy.
- `test_database.py`: Advisory lock kepemilikan bot dan koneksi async PostgreSQL.
- `test_telegram_intake.py` & `test_telegram_sender.py`: Penanganan intake pesan, chunking UTF-16, dan toleransi retry.
