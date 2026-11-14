"""Internal daily summary endpoint called by the Java backend (TASK-080 D1/D2).

Sends one outlet's end-of-day summary to the tenant's owners on Telegram. The request
body, chat ids and the shared key are never logged or echoed in a response.
"""

import asyncio
import logging
from datetime import date
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError
from sqlalchemy.exc import SQLAlchemyError

from app.api.internal_members import (
    MAX_BODY_BYTES,
    _authorize,
    _error_fields,
    _json,
    _validation_error,
    get_internal_api_settings,
)
from app.channels.telegram.sender import split_chunks
from app.chat.daily_summary import build_summary, request_insight
from app.chat.repositories import list_telegram_owner_recipients
from app.config import InternalApiSettings
from app.core.database import OwnershipLost
from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import load_grpc_settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/internal", tags=["internal"])

MAX_SEND_ATTEMPTS = 3


class DailySummaryRequest(BaseModel):
    tenant_id: UUID
    outlet_id: UUID
    business_date: date


class DailySummaryResponse(BaseModel):
    delivered: int
    skipped: int


def get_grpc_client() -> CashierGrpcClient:
    return CashierGrpcClient(load_grpc_settings())


def _outlet_section(data: dict[str, Any] | None, outlet_id: str) -> dict[str, Any] | None:
    for section in (data or {}).get("outlets", []):
        if section.get("outlet_id") == outlet_id:
            return section
    return None


async def _send(channel: Any, chat_id: int, text: str) -> bool:
    """Every chunk must be SENT; PENDING (rate limit / server error) is retried a few times."""
    for chunk in split_chunks(text):
        for attempt in range(1, MAX_SEND_ATTEMPTS + 1):
            delivery = await channel.intake.sender.send(chat_id, chunk, attempt)
            if delivery.status != "PENDING":
                break
            await asyncio.sleep(min(delivery.retry_after, 10))
        if delivery.status != "SENT":
            return False
    return True


async def _summary_for(
    client: CashierGrpcClient, tenant_id: str, user_id: str, outlet_id: str, day: date
) -> str | None:
    iso = day.isoformat()
    summary = _outlet_section(
        await client.get_sales_summary(
            tenant_id=tenant_id, user_id=user_id, start_date=iso, end_date=iso
        ),
        outlet_id,
    )
    if summary is None:
        return None
    restock = _outlet_section(
        await client.get_restock_suggestions(tenant_id=tenant_id, user_id=user_id), outlet_id
    )
    anomalies = _outlet_section(
        await client.get_anomalies(tenant_id=tenant_id, user_id=user_id, date=iso), outlet_id
    )
    text = build_summary(summary, day, restock, anomalies)
    insight = await request_insight(text)
    return f"{text}\n\nCatatan: {insight}" if insight else text


@router.post(
    "/daily-summaries",
    response_model=DailySummaryResponse,
    summary="Send one outlet's daily summary to the tenant owners on Telegram",
)
async def post_daily_summary(
    request: Request,
    settings: Annotated[InternalApiSettings, Depends(get_internal_api_settings)],
    client: Annotated[CashierGrpcClient, Depends(get_grpc_client)],
) -> JSONResponse | DailySummaryResponse:
    denied = _authorize(request, settings)
    if denied is not None:
        return denied
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        return _validation_error(["body"])
    try:
        data = DailySummaryRequest.model_validate_json(body)
    except ValidationError as error:
        return _validation_error(_error_fields(error))
    channel = getattr(request.app.state, "telegram_channel", None)
    if channel is None or not await channel.ready():
        return _json(503, "service_unavailable")
    try:
        async with channel.database.transaction() as connection:
            recipients = await list_telegram_owner_recipients(connection, data.tenant_id)
    except (SQLAlchemyError, OwnershipLost, TimeoutError) as error:
        logger.error("Daily summary recipient lookup failed: %s", type(error).__name__)
        return _json(503, "service_unavailable")
    delivered = skipped = 0
    tenant_id, outlet_id = str(data.tenant_id), str(data.outlet_id)
    async with client:
        for user_id, chat_id in recipients:
            try:
                text = await _summary_for(
                    client, tenant_id, str(user_id), outlet_id, data.business_date
                )
                sent = text is not None and await _send(channel, chat_id, text)
            except Exception as error:  # one recipient must not stop the others
                logger.warning(
                    "Daily summary recipient failed: %s",
                    type(error).__name__,
                    extra={"event": "daily_summary_recipient_failed"},
                )
                sent = False
            delivered += sent
            skipped += not sent
    logger.info(
        "Daily summary dispatched",
        extra={"event": "daily_summary_done", "delivered": delivered, "skipped": skipped},
    )
    return DailySummaryResponse(delivered=delivered, skipped=skipped)
