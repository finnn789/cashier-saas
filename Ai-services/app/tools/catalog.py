"""Domain tool: Catalog check for Cashier SaaS via gRPC."""

from typing import Any

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import load_grpc_settings
from app.tools.base import BaseTool


class CatalogSearchTool(BaseTool):
    """Tool for searching items/prices in the merchant's catalog."""

    def __init__(self, client: CashierGrpcClient | None = None) -> None:
        self._client = client

    @property
    def name(self) -> str:
        return "search_catalog_menu"

    @property
    def description(self) -> str:
        return (
            "Mencari informasi menu, harga, atau ketersediaan makanan dan minuman "
            "berdasarkan nama item di katalog kasir."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Kata kunci nama menu/item yang dicari, misal: 'kopi', 'nasi goreng'.",
                }
            },
            "required": ["query"],
        }

    async def execute(
        self, *, tenant_id: str, user_id: str | None = None, query: str = "", **kwargs: Any
    ) -> Any:
        clean_query = query.strip()
        if not user_id:
            return {
                "query": clean_query,
                "total": 0,
                "items": [],
                "message": "Katalog tidak tersedia: akun Anda belum terverifikasi.",
            }
        client = self._client
        if client is None:
            client = CashierGrpcClient(load_grpc_settings())
            self._client = client

        items = await client.get_catalog_items(
            tenant_id=tenant_id, user_id=user_id, query=clean_query
        )
        return {
            "query": clean_query,
            "total": len(items),
            "items": [{**item, "price": str(item["price"])} for item in items],
            "message": (
                f"Ditemukan {len(items)} menu untuk pencarian '{clean_query}'."
                if items
                else f"Tidak ditemukan menu dengan kata kunci '{clean_query}'."
            ),
        }
