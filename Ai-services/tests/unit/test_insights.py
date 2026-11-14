"""TASK-080: owner insight gRPC client methods and the three AI tools (offline, mock stubs)."""

import unittest
from unittest.mock import AsyncMock, Mock

import grpc
import grpc.aio

from app.integrations.grpc.client import SERVICE_KEY_HEADER, CashierGrpcClient
from app.integrations.grpc.config import GrpcSettings
from app.integrations.grpc.generated import cashier_service_pb2 as pb
from app.tools.insights import (
    UNAVAILABLE,
    CashierAnomaliesTool,
    RestockSuggestionsTool,
    SalesSummaryTool,
)

SECRET = "unit-test-secret-key-0123456789"
TENANT = "3f2b8c1e-5a47-4c6e-9d11-0a1b2c3d4e5f"
USER = "7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a40"
OUTLET = "9c1d2e3f-0000-4000-8000-000000000001"
CLIENT_LOGGER = "app.integrations.grpc.client"


def _rpc_error(code: grpc.StatusCode) -> grpc.aio.AioRpcError:
    return grpc.aio.AioRpcError(
        code=code, initial_metadata=(), trailing_metadata=(), details="denied", debug_error_string=""
    )


def _client(service_key: str = SECRET) -> CashierGrpcClient:
    client = CashierGrpcClient(GrpcSettings(host="127.0.0.1", port=9090, service_key=service_key))
    client._channel = Mock()
    client._stub = Mock()
    return client


# (client method, stub rpc name, extra call kwargs, request class, response factory)
CASES = [
    (
        "get_sales_summary",
        "GetSalesSummary",
        {"start_date": "2026-11-01", "end_date": "2026-11-07"},
        pb.GetSalesSummaryRequest,
        lambda: pb.GetSalesSummaryResponse(tenant_id=TENANT),
    ),
    (
        "get_restock_suggestions",
        "GetRestockSuggestions",
        {},
        pb.GetRestockSuggestionsRequest,
        lambda: pb.GetRestockSuggestionsResponse(tenant_id=TENANT),
    ),
    (
        "get_anomalies",
        "GetAnomalies",
        {"date": "2026-11-07"},
        pb.GetAnomaliesRequest,
        lambda: pb.GetAnomaliesResponse(tenant_id=TENANT),
    ),
]


class InsightClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_sales_summary_maps_nested_message_to_plain_dict(self) -> None:
        client = _client()
        response = pb.GetSalesSummaryResponse(
            tenant_id=TENANT,
            outlets=[
                pb.OutletSalesSummary(
                    outlet_id=OUTLET,
                    outlet_name="Kedai Senja",
                    transaction_count=12,
                    net_sales="150000.00",
                    items_without_cost=2,
                    labels=[pb.LabelTotal(method="CASH", label="Tunai", count=7, net_sales="90000")],
                    top_products=[
                        pb.ProductTotal(name="Kopi Susu", quantity=9, gross_sales="162000")
                    ],
                )
            ],
        )
        client._stub.GetSalesSummary = AsyncMock(return_value=response)

        result = await client.get_sales_summary(
            tenant_id=TENANT, user_id=USER, start_date="2026-11-01", end_date="2026-11-07"
        )

        request = client._stub.GetSalesSummary.await_args.args[0]
        self.assertIsInstance(request, pb.GetSalesSummaryRequest)
        self.assertEqual(
            (request.tenant_id, request.user_id, request.start_date, request.end_date),
            (TENANT, USER, "2026-11-01", "2026-11-07"),
        )
        self.assertEqual(result["tenant_id"], TENANT)
        outlet = result["outlets"][0]
        self.assertIsInstance(outlet, dict)
        self.assertEqual(outlet["outlet_id"], OUTLET)
        self.assertEqual(outlet["transaction_count"], 12)  # int64 stays int
        self.assertEqual(outlet["net_sales"], "150000.00")  # money stays a string
        self.assertEqual(outlet["items_without_cost"], 2)
        self.assertEqual(
            outlet["labels"],
            [{"method": "CASH", "label": "Tunai", "count": 7, "net_sales": "90000"}],
        )
        self.assertEqual(outlet["top_products"][0]["name"], "Kopi Susu")
        self.assertEqual(outlet["top_products"][0]["quantity"], 9)

    async def test_restock_and_anomalies_map_repeated_sections(self) -> None:
        client = _client()
        client._stub.GetRestockSuggestions = AsyncMock(
            return_value=pb.GetRestockSuggestionsResponse(
                tenant_id=TENANT,
                outlets=[
                    pb.OutletRestock(
                        outlet_id=OUTLET,
                        outlet_name="Kedai Senja",
                        items=[
                            pb.RestockSuggestion(
                                inventory_item_id="i1",
                                name="Susu",
                                unit="L",
                                quantity_on_hand="1.500",
                                suggested_quantity="6.250",
                                stock_status="LOW",
                            )
                        ],
                    )
                ],
            )
        )
        client._stub.GetAnomalies = AsyncMock(
            return_value=pb.GetAnomaliesResponse(
                tenant_id=TENANT,
                outlets=[
                    pb.OutletAnomalies(
                        outlet_id=OUTLET,
                        date="2026-11-07",
                        anomalies=[
                            pb.CashierAnomaly(
                                staff_user_id="s1",
                                full_name="Sari",
                                kind="CANCELLATIONS",
                                value="5",
                                baseline_daily_average="1.20",
                            )
                        ],
                    )
                ],
            )
        )

        restock = await client.get_restock_suggestions(tenant_id=TENANT, user_id=USER)
        anomalies = await client.get_anomalies(tenant_id=TENANT, user_id=USER, date="2026-11-07")

        item = restock["outlets"][0]["items"][0]
        self.assertEqual(
            (item["name"], item["suggested_quantity"], item["stock_status"]),
            ("Susu", "6.250", "LOW"),
        )
        flagged = anomalies["outlets"][0]["anomalies"][0]
        self.assertEqual(
            (flagged["kind"], flagged["value"], flagged["baseline_daily_average"]),
            ("CANCELLATIONS", "5", "1.20"),
        )
        request = client._stub.GetAnomalies.await_args.args[0]
        self.assertEqual((request.user_id, request.date), (USER, "2026-11-07"))

    async def test_each_method_sends_service_key_metadata_and_timeout(self) -> None:
        for method, rpc, kwargs, request_class, response in CASES:
            with self.subTest(method=method):
                client = _client()
                setattr(client._stub, rpc, AsyncMock(return_value=response()))

                result = await getattr(client, method)(tenant_id=TENANT, user_id=USER, **kwargs)

                self.assertEqual(result["tenant_id"], TENANT)
                call = getattr(client._stub, rpc).await_args
                self.assertIsInstance(call.args[0], request_class)
                self.assertEqual(call.kwargs["metadata"], ((SERVICE_KEY_HEADER, SECRET),))
                self.assertEqual(call.kwargs["timeout"], client._settings.timeout_seconds)

    async def test_rpc_error_including_permission_denied_returns_none(self) -> None:
        for code in (grpc.StatusCode.PERMISSION_DENIED, grpc.StatusCode.UNAVAILABLE):
            for method, rpc, kwargs, _, _ in CASES:
                with self.subTest(method=method, code=code.name):
                    client = _client()
                    setattr(client._stub, rpc, AsyncMock(side_effect=_rpc_error(code)))
                    with self.assertLogs(CLIENT_LOGGER, level="WARNING") as logs:
                        result = await getattr(client, method)(
                            tenant_id=TENANT, user_id=USER, **kwargs
                        )
                    self.assertIsNone(result)
                    self.assertTrue(any(code.name in line for line in logs.output))
                    self.assertFalse(any(SECRET in line for line in logs.output))

    async def test_unexpected_error_returns_none(self) -> None:
        for method, rpc, kwargs, _, _ in CASES:
            with self.subTest(method=method):
                client = _client()
                setattr(client._stub, rpc, AsyncMock(side_effect=RuntimeError("boom")))
                result = await getattr(client, method)(tenant_id=TENANT, user_id=USER, **kwargs)
                self.assertIsNone(result)

    async def test_consume_ai_question_maps_the_quota_decision(self) -> None:
        client = _client()
        client._stub.ConsumeAiQuestion = AsyncMock(
            return_value=pb.ConsumeAiQuestionResponse(
                allowed=False, reason="DAILY_CAP_REACHED", used=40, limit=300, warn=True
            )
        )

        result = await client.consume_ai_question(tenant_id=TENANT, user_id=USER)

        request = client._stub.ConsumeAiQuestion.await_args.args[0]
        self.assertEqual((request.tenant_id, request.user_id), (TENANT, USER))
        self.assertEqual(
            result,
            {"allowed": False, "reason": "DAILY_CAP_REACHED", "used": 40, "limit": 300, "warn": True},
        )

    async def test_consume_ai_question_returns_none_on_rpc_error_so_the_caller_fails_closed(self) -> None:
        client = _client()
        client._stub.ConsumeAiQuestion = AsyncMock(
            side_effect=_rpc_error(grpc.StatusCode.PERMISSION_DENIED)
        )
        with self.assertLogs(CLIENT_LOGGER, level="WARNING"):
            self.assertIsNone(await client.consume_ai_question(tenant_id=TENANT, user_id=USER))

    async def test_record_ai_usage_sends_tenant_and_token_counts(self) -> None:
        client = _client()
        client._stub.RecordAiUsage = AsyncMock(return_value=pb.RecordAiUsageResponse())

        await client.record_ai_usage(tenant_id=TENANT, input_tokens=1200, output_tokens=345)

        request = client._stub.RecordAiUsage.await_args.args[0]
        self.assertEqual(
            (request.tenant_id, request.input_tokens, request.output_tokens), (TENANT, 1200, 345)
        )

    async def test_record_ai_usage_swallows_errors(self) -> None:
        for error in (_rpc_error(grpc.StatusCode.UNAVAILABLE), RuntimeError("boom")):
            with self.subTest(error=type(error).__name__):
                client = _client()
                client._stub.RecordAiUsage = AsyncMock(side_effect=error)
                with self.assertLogs(CLIENT_LOGGER, level="WARNING"):
                    await client.record_ai_usage(tenant_id=TENANT, input_tokens=1, output_tokens=1)

    async def test_empty_service_key_fails_closed_without_calling_backend(self) -> None:
        for method, rpc, kwargs, _, _ in CASES:
            with self.subTest(method=method):
                client = _client(service_key="")
                setattr(client._stub, rpc, AsyncMock())
                with self.assertLogs(CLIENT_LOGGER, level="WARNING"):
                    result = await getattr(client, method)(tenant_id=TENANT, user_id=USER, **kwargs)
                self.assertIsNone(result)
                getattr(client._stub, rpc).assert_not_called()


def _mock_client(**results: object) -> Mock:
    client = Mock(spec=CashierGrpcClient)
    for method, value in results.items():
        setattr(client, method, AsyncMock(return_value=value))
    return client


class InsightToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_success_passes_trusted_user_and_stripped_dates(self) -> None:
        payload = {"outlets": [{"outlet_id": OUTLET}]}
        client = _mock_client(
            get_sales_summary=payload, get_restock_suggestions=payload, get_anomalies=payload
        )

        sales = await SalesSummaryTool(client).execute(
            tenant_id=TENANT, user_id=USER, start_date=" 2026-11-01 ", end_date="2026-11-07"
        )
        restock = await RestockSuggestionsTool(client).execute(tenant_id=TENANT, user_id=USER)
        anomalies = await CashierAnomaliesTool(client).execute(
            tenant_id=TENANT, user_id=USER, date="2026-11-07"
        )

        self.assertEqual((sales, restock, anomalies), (payload, payload, payload))
        client.get_sales_summary.assert_awaited_once_with(
            tenant_id=TENANT, user_id=USER, start_date="2026-11-01", end_date="2026-11-07"
        )
        client.get_restock_suggestions.assert_awaited_once_with(tenant_id=TENANT, user_id=USER)
        client.get_anomalies.assert_awaited_once_with(
            tenant_id=TENANT, user_id=USER, date="2026-11-07"
        )

    async def test_none_from_client_gives_owner_only_message(self) -> None:
        client = _mock_client(
            get_sales_summary=None, get_restock_suggestions=None, get_anomalies=None
        )
        results = [
            await SalesSummaryTool(client).execute(
                tenant_id=TENANT, user_id=USER, start_date="2026-11-01", end_date="2026-11-07"
            ),
            await RestockSuggestionsTool(client).execute(tenant_id=TENANT, user_id=USER),
            await CashierAnomaliesTool(client).execute(
                tenant_id=TENANT, user_id=USER, date="2026-11-07"
            ),
        ]
        for result in results:
            self.assertEqual(result, UNAVAILABLE)
            self.assertIn("owner", result["message"])
            self.assertIn("tidak tersedia", result["message"])

    async def test_missing_user_id_is_unavailable_without_calling_backend(self) -> None:
        client = _mock_client(get_sales_summary={}, get_restock_suggestions={}, get_anomalies={})
        for user_id in (None, ""):
            self.assertEqual(
                await SalesSummaryTool(client).execute(
                    tenant_id=TENANT, user_id=user_id, start_date="2026-11-01", end_date="2026-11-07"
                ),
                UNAVAILABLE,
            )
            self.assertEqual(
                await RestockSuggestionsTool(client).execute(tenant_id=TENANT, user_id=user_id),
                UNAVAILABLE,
            )
            self.assertEqual(
                await CashierAnomaliesTool(client).execute(
                    tenant_id=TENANT, user_id=user_id, date="2026-11-07"
                ),
                UNAVAILABLE,
            )
        client.get_sales_summary.assert_not_called()
        client.get_restock_suggestions.assert_not_called()
        client.get_anomalies.assert_not_called()

    async def test_invalid_dates_are_rejected_before_any_call(self) -> None:
        client = _mock_client(get_sales_summary={}, get_anomalies={})
        for bad in ("", "kemarin", "2026-13-01", "07-11-2026"):
            with self.subTest(value=bad):
                start = await SalesSummaryTool(client).execute(
                    tenant_id=TENANT, user_id=USER, start_date=bad, end_date="2026-11-07"
                )
                end = await SalesSummaryTool(client).execute(
                    tenant_id=TENANT, user_id=USER, start_date="2026-11-01", end_date=bad
                )
                one_day = await CashierAnomaliesTool(client).execute(
                    tenant_id=TENANT, user_id=USER, date=bad
                )
                self.assertEqual(start["status"], "invalid_input")
                self.assertIn("start_date", start["message"])
                self.assertIn("end_date", end["message"])
                self.assertEqual(one_day["status"], "invalid_input")
                self.assertIn("date", one_day["message"])
        client.get_sales_summary.assert_not_called()
        client.get_anomalies.assert_not_called()


if __name__ == "__main__":
    unittest.main()
