"""TASK-116: product_analysis tool (offline, mocked gRPC client)."""

import unittest
from unittest.mock import AsyncMock, Mock

from app.integrations.grpc.client import CashierGrpcClient
from app.tools.insights import UNAVAILABLE
from app.tools.product_analysis import ProductAnalysisTool

TENANT = "3f2b8c1e-5a47-4c6e-9d11-0a1b2c3d4e5f"
USER = "7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a40"


def _product(name: str, quantity: int, gross: int, cogs: int = 0) -> dict:
    return {
        "product_id": name.lower(),
        "name": name,
        "quantity": quantity,
        "gross_sales": str(gross),
        "cost_of_goods_sold": str(cogs),
        "items_without_cost": 0,
    }


def _perf(*products: dict) -> dict:
    return {"outlets": [{"outlet_id": "o1", "outlet_name": "Kedai Senja", "products": list(products)}]}


def _tool(**results: object) -> tuple[ProductAnalysisTool, Mock]:
    client = Mock(spec=CashierGrpcClient)
    client.get_product_performance = AsyncMock(**results)
    return ProductAnalysisTool(client), client


class ProductAnalysisToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_input_and_unavailable_never_reach_or_survive_the_rpc(self) -> None:
        tool, client = _tool(return_value=_perf())
        base = {"tenant_id": TENANT, "user_id": USER, "kind": "abc"}
        for start, end, kind in (
            ("kemarin", "2024-03-07", "abc"),
            ("2024-03-01", "2024-13-07", "abc"),
            ("2024-03-08", "2024-03-07", "abc"),  # start after end
            ("2024-03-01", "2024-03-07", "unknown"),
        ):
            with self.subTest(start=start, end=end, kind=kind):
                result = await tool.execute(**{**base, "start_date": start, "end_date": end, "kind": kind})
                self.assertEqual(result["status"], "invalid_input")
        client.get_product_performance.assert_not_called()

        dates = {"start_date": "2024-03-01", "end_date": "2024-03-07", "kind": "abc"}
        self.assertEqual(await tool.execute(tenant_id=TENANT, user_id=None, **dates), UNAVAILABLE)
        client.get_product_performance.assert_not_called()

        none_tool, _ = _tool(return_value=None)
        self.assertEqual(await none_tool.execute(tenant_id=TENANT, user_id=USER, **dates), UNAVAILABLE)

    async def test_abc_text_has_rupiah_sales_and_class_letters(self) -> None:
        tool, _ = _tool(
            return_value=_perf(_product("Kopi", 10, 500000), _product("Teh", 5, 400000), _product("Roti", 1, 100000))
        )

        text = await tool.execute(
            tenant_id=TENANT, user_id=USER, start_date="2024-03-01", end_date="2024-03-07", kind="abc"
        )

        self.assertIn("Kedai Senja", text)
        self.assertRegex(text, r"Kopi.*Rp500\.000.*\bA\b")
        self.assertRegex(text, r"Roti.*Rp100\.000.*\bB\b")

    async def test_outlet_without_products_says_no_sales(self) -> None:
        tool, _ = _tool(return_value=_perf())
        text = await tool.execute(
            tenant_id=TENANT, user_id=USER, start_date="2024-03-01", end_date="2024-03-07", kind="abc"
        )
        self.assertIn("Belum ada penjualan.", text)

    async def test_growth_calls_previous_and_current_period_and_marks_new_products(self) -> None:
        previous = _perf(_product("Kopi", 5, 100000))
        current = _perf(_product("Kopi", 8, 150000), _product("Matcha", 3, 60000))

        async def respond(*, tenant_id, user_id, start_date, end_date):
            return previous if start_date == "2024-03-01" else current

        tool, client = _tool(side_effect=respond)

        text = await tool.execute(
            tenant_id=TENANT, user_id=USER, start_date="2024-03-08", end_date="2024-03-14", kind="growth"
        )

        self.assertCountEqual(
            [(c.kwargs["start_date"], c.kwargs["end_date"]) for c in client.get_product_performance.await_args_list],
            [("2024-03-01", "2024-03-07"), ("2024-03-08", "2024-03-14")],
        )
        self.assertRegex(text, r"Kopi.*\(50%\)")
        self.assertRegex(text, r"Matcha.*baru")


if __name__ == "__main__":
    unittest.main()
