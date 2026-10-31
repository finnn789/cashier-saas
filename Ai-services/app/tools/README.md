# Pengelolaan AI Tools (`app/tools`)

Paket ini mengelola seluruh tools yang dapat dipanggil oleh LLM (OpenRouter) melalui mekanisme Function Calling / Tool Calling dengan jaminan isolasi tenant (*tenant isolation*).

## Struktur Modul

- [`base.py`](base.py): Abstract base class [`BaseTool`](base.py) untuk setiap tool. Mengharuskan implementasi properti `name`, `description`, `parameters` (JSON Schema), serta method async `execute(tenant_id, **kwargs)`.
- [`registry.py`](registry.py): Class [`ToolRegistry`](registry.py) untuk mendaftarkan tool, mengekspor definisi skema OpenRouter, dan melakukan dispatch eksekusi tool secara aman.
- [`catalog.py`](catalog.py): Contoh implementasi tool [`CatalogSearchTool`](catalog.py) untuk pencarian menu katalog kasir.

## Cara Menambahkan Tool Baru

1. Buat file baru di direktori ini, misalnya `order.py`.
2. Buat subclass dari `BaseTool`:

```python
from typing import Any
from app.tools.base import BaseTool

class OrderStatusTool(BaseTool):
    @property
    def name(self) -> str:
        return "check_order_status"

    @property
    def description(self) -> str:
        return "Mengecek status terkini dari pesanan berdasarkan order token."

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "order_token": {
                    "type": "string",
                    "description": "Token atau kode pesanan pelanggan"
                }
            },
            "required": ["order_token"]
        }

    async def execute(self, *, tenant_id: str, order_token: str = "", **kwargs: Any) -> Any:
        # Jalankan query ke database atau backend service dengan tenant_id terikat
        return {"order_token": order_token, "status": "PENDING"}
```

3. Daftarkan instance tool ke `ToolRegistry`:

```python
from app.tools import ToolRegistry, CatalogSearchTool
from app.tools.order import OrderStatusTool

registry = ToolRegistry()
registry.register(CatalogSearchTool())
registry.register(OrderStatusTool())
```

4. Gunakan `registry.get_openrouter_schemas()` untuk menyuplai parameter `tools` ke request OpenRouter, dan gunakan `await registry.dispatch(...)` saat menangani balasan `finish_reason == "tool_calls"`.
