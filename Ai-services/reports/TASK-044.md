# TASK-044 — AI Service gRPC Setup & Tool Execution Planning Report

**Date**: 2026-10-22  
**Status**: IMPLEMENTATION COMPLETE & VERIFIED / READY FOR TESTING  
**Scope**: `Ai-services` (Python 3.12, FastAPI, `app/tools`, `app/integrations/grpc`)  
**Worker**: Worker 2 (AI Tools & Test Engineer)  

---

## 1. Executive Summary & Objective

Pengguna meminta perancangan dan persiapan setup **gRPC** pada `Ai-services` agar tools yang berada di dalam `app/tools/` dapat mengeksekusi RPC ke backend service (seperti Spring Boot POS/Cashier backend) secara asinkron, aman, dan terisolasi per tenant.

Implementasi diselesaikan dalam dua fase:
1. **Fase 1 (Infrastruktur gRPC & Protobuf)**:
   - Kontrak Protobuf pada `proto/cashier_service.proto`.
   - Kode stub hasil kompilasi protoc di `app/integrations/grpc/generated/`.
   - Konfigurasi `GrpcSettings` di `app/integrations/grpc/config.py` dan root `Settings` di `app/config.py`.
   - Klien asinkron mandiri `CashierGrpcClient` di `app/integrations/grpc/client.py`.
2. **Fase 2 (Integrasi Tool & Pengujian Unit)**:
   - Pembaruan `CatalogSearchTool` (`app/tools/catalog.py`) dengan integrasi `CashierGrpcClient.get_catalog_items` dan fallback offline.
   - Pembuatan `OrderStatusTool` (`app/tools/order.py`) dengan integrasi `CashierGrpcClient.get_order_status` dan struktur respons LLM.
   - Ekspor dan registrasi `OrderStatusTool` ke `get_default_tool_registry()` di `app/tools/__init__.py`.
   - Suite unit test komprehensif `tests/unit/test_grpc_tools.py` (17 tests, 100% lulus).
   - Pembaruan dokumentasi `README.md` dengan instruksi gRPC, protoc, dan variabel lingkungan.

---

## 2. Arsitektur & Desain Teknis

```mermaid
flowchart LR
    A["User Chat / Telegram"] --> B["ChatService / OpenRouter"]
    B --> C["ToolRegistry (app/tools)"]
    C --> D["CatalogSearchTool / OrderStatusTool"]
    D --> E["CashierGrpcClient (grpc.aio)"]
    E -->|gRPC Call (with tenant_id metadata)| F["Backend Service (Spring Boot / Mock)"]
```

### A. Dependensi & Lingkungan (`pyproject.toml`)
Menambahkan pustaka runtime dan compiler gRPC yang kompatibel dengan Python 3.12:
- `grpcio >= 1.62.0, < 2.0` : Core async gRPC engine (`grpc.aio`).
- `grpcio-tools >= 1.62.0, < 2.0` : Protoc compiler untuk generasi Python stubs.
- `protobuf >= 4.25.0, < 6.0` : Protobuf serialization runtime.

### B. Struktur Kontrak Protobuf (`proto/cashier_service.proto`)
Membuat kontrak service minimal untuk read operations:
1. `GetCatalogItem`:
   - Request: `tenant_id` (string), `query` (string), `limit` (int32).
   - Response: `repeated CatalogItem items` (id, name, price, is_available, category).
2. `GetOrderStatus`:
   - Request: `tenant_id` (string), `order_number` (string).
   - Response: `tenant_id`, `order_number`, `status`, `payment_status`, `total_amount`, `created_at`.
   *(Tidak mengekspos token publik, hash rahasia, atau data pembayaran sensitif internal)*.

### C. Client Asinkron (`app/integrations/grpc/`)
- **`config.py`**: Konfigurasi `GrpcSettings` (host, port, timeout default 5s, secure/insecure TLS).
- **`client.py`**:
  - Menggunakan `grpc.aio.insecure_channel` (atau secure jika TLS aktif).
  - Menyediakan singleton channel dan stub connection pool yang aman.
  - Tangani `grpc.aio.AioRpcError` (e.g. `DEADLINE_EXCEEDED`, `UNAVAILABLE`, `NOT_FOUND`) dengan fallback `[]` atau `None`.
  - Penutupan channel (`channel.close()`) saat shutdown aplikasi.
- **`generated/`**: Kompilasi stubs `cashier_service_pb2.py` dan `cashier_service_pb2_grpc.py`.

### D. Pipeline Eksekusi Tool (`app/tools/`)
- **`CatalogSearchTool` (`app/tools/catalog.py`)**:
  - Menerima opsi `client: CashierGrpcClient | None = None` (lazy initialize dari `load_grpc_settings()` jika None).
  - Mengeksekusi RPC `get_catalog_items(tenant_id=tenant_id, query=clean_query)`.
  - Mengembalikan struktur LLM: `{"query": clean_query, "total": len(items), "items": items, "message": "..."}`.
- **`OrderStatusTool` (`app/tools/order.py`)**:
  - Tool baru mewarisi `BaseTool` dengan nama `check_order_status`.
  - Parameter JSON Schema: `order_number` (string).
  - Validasi argumen dan RPC `get_order_status(tenant_id=tenant_id, order_number=clean_order_number)`.
  - Mengembalikan struktur terstandarisasi dengan fallback `status="not_found_or_unavailable"` jika order tidak ditemukan atau backend offline.
- **`ToolRegistry` (`app/tools/__init__.py`)**:
  - `get_default_tool_registry()` mendaftarkan 3 default tools: `CpuUsageTool`, `CatalogSearchTool`, dan `OrderStatusTool`.

---

## 3. Kompilasi Protobuf Workflow

Perintah standard untuk build stubs:
```powershell
uv run python -m grpc_tools.protoc -Iproto --python_out=app/integrations/grpc/generated --grpc_python_out=app/integrations/grpc/generated proto/cashier_service.proto
```
File `__init__.py` pada `app/integrations/grpc/generated` disiapkan untuk mendukung relative import secara bersih.

---

## 4. File Reservations & Status Perubahan

| File | Status | Keterangan |
| :--- | :--- | :--- |
| `pyproject.toml` | Diselesaikan (Fase 1) | Dependensi `grpcio`, `grpcio-tools`, `protobuf` |
| `proto/cashier_service.proto` | Diselesaikan (Fase 1) | Kontrak catalog & order inquiry |
| `app/integrations/grpc/__init__.py` | Diselesaikan (Fase 1) | Ekspor client & config |
| `app/integrations/grpc/config.py` | Diselesaikan (Fase 1) | Dataclass `GrpcSettings` & loader env |
| `app/integrations/grpc/client.py` | Diselesaikan (Fase 1) | Asynchronous client wrapper gRPC |
| `app/integrations/grpc/generated/` | Diselesaikan (Fase 1) | Stubs protoc & package init |
| `app/config.py` | Diselesaikan (Fase 1) | Integrasi `grpc` ke root `Settings` |
| `app/tools/catalog.py` | Diselesaikan (Fase 2) | Integrasi gRPC client & response format |
| `app/tools/order.py` | Diselesaikan (Fase 2) | Pembuatan `OrderStatusTool` |
| `app/tools/__init__.py` | Diselesaikan (Fase 2) | Ekspor & registrasi `OrderStatusTool` |
| `tests/unit/test_grpc_tools.py` | Diselesaikan (Fase 2) | Suite unit test 17 skenario gRPC |
| `tests/unit/test_tools.py` | Diperbarui (Fase 2) | Penyesuaian ekspektasi 3 default tools |
| `README.md` | Diselesaikan (Fase 2) | Dokumentasi gRPC, env, protoc, order tool |
| `reports/TASK-044.md` | Diselesaikan (Fase 2) | Laporan verifikasi dan ringkasan |

---

## 5. Bukti Verifikasi & Pengujian

### A. Verifikasi Sintaksis AST
Semua file Python yang diubah dan dibuat lolos parsing AST Python standard library tanpa kesalahan:
```
uv run python -c "import ast; files = ['app/tools/catalog.py', 'app/tools/order.py', 'app/tools/__init__.py', 'tests/unit/test_grpc_tools.py', 'tests/unit/test_tools.py']; [ast.parse(open(f, encoding='utf-8').read(), f) for f in files]; print('ALL AST PARSED OK')"
>> ALL AST PARSED OK
```

### B. Eksekusi Unit Test Suite gRPC Tools (`tests/unit/test_grpc_tools.py`)
```
uv run python -m unittest tests/unit/test_grpc_tools.py -v
```
Hasil:
```
test_execute_empty_or_offline (tests.unit.test_grpc_tools.CatalogSearchToolTests.test_execute_empty_or_offline) ... ok
test_execute_with_items_returned (tests.unit.test_grpc_tools.CatalogSearchToolTests.test_execute_with_items_returned) ... ok
test_lazy_client_initialization (tests.unit.test_grpc_tools.CatalogSearchToolTests.test_lazy_client_initialization) ... ok
test_tool_schema (tests.unit.test_grpc_tools.CatalogSearchToolTests.test_tool_schema) ... ok
test_default_tool_registry_contains_all_tools (tests.unit.test_grpc_tools.DefaultToolRegistryTests.test_default_tool_registry_contains_all_tools) ... ok
test_dispatch_catalog_and_order_tools (tests.unit.test_grpc_tools.DefaultToolRegistryTests.test_dispatch_catalog_and_order_tools) ... ok
test_catalog_offline_fallback (tests.unit.test_grpc_tools.GrpcClientTests.test_catalog_offline_fallback) ... ok
test_catalog_rpc_error_handling (tests.unit.test_grpc_tools.GrpcClientTests.test_catalog_rpc_error_handling) ... ok
test_client_lazy_channel_and_close (tests.unit.test_grpc_tools.GrpcClientTests.test_client_lazy_channel_and_close) ... ok
test_order_status_not_found_rpc_error (tests.unit.test_grpc_tools.GrpcClientTests.test_order_status_not_found_rpc_error) ... ok
test_order_status_offline_fallback (tests.unit.test_grpc_tools.GrpcClientTests.test_order_status_offline_fallback) ... ok
test_settings_defaults (tests.unit.test_grpc_tools.GrpcClientTests.test_settings_defaults) ... ok
test_execute_empty_order_number (tests.unit.test_grpc_tools.OrderStatusToolTests.test_execute_empty_order_number) ... ok
test_execute_order_found (tests.unit.test_grpc_tools.OrderStatusToolTests.test_execute_order_found) ... ok
test_execute_order_not_found_or_offline (tests.unit.test_grpc_tools.OrderStatusToolTests.test_execute_order_not_found_or_offline) ... ok
test_lazy_client_initialization (tests.unit.test_grpc_tools.OrderStatusToolTests.test_lazy_client_initialization) ... ok
test_tool_schema (tests.unit.test_grpc_tools.OrderStatusToolTests.test_tool_schema) ... ok

----------------------------------------------------------------------
Ran 17 tests in 0.554s

OK
```

### C. Eksekusi `tests/unit/test_tools.py`
```
uv run python -m unittest tests/unit/test_tools.py -v
```
Hasil:
```
test_cpu_usage_tool_schema_and_execution (tests.unit.test_tools.ToolsTests.test_cpu_usage_tool_schema_and_execution) ... ok
test_default_tool_registry (tests.unit.test_tools.ToolsTests.test_default_tool_registry) ... ok
test_registry_registration_and_dispatch (tests.unit.test_tools.ToolsTests.test_registry_registration_and_dispatch) ... ok

----------------------------------------------------------------------
Ran 3 tests in 0.416s

OK
```

Total pengujian pada subsistem tools & gRPC: **20 tes lulus tanpa error (0 failures, 0 errors)**.

---

## 6. Status Handoff

**READY FOR TESTING**. Implementasi Fase 2 TASK-044 selesai secara penuh, terisolasi per tenant, aman terhadap kegagalan jaringan backend (offline-safe), dan lulus seluruh pengujian unit fungsional yang ditugaskan.
