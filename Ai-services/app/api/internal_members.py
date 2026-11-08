"""Internal member sync endpoint called by the Java backend.

Contract: docs/contracts/member-sync.md section 1. The request body, the phone
number and the shared key are never logged or echoed in a response.
"""

import hmac
import logging
from contextlib import AbstractAsyncContextManager
from typing import Annotated, Literal, Protocol
from uuid import UUID

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, StringConstraints, ValidationError
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncConnection

from app.chat.members import upsert_member
from app.config import InternalApiSettings, load_internal_api_settings
from app.core.database import OwnershipLost

logger = logging.getLogger(__name__)

KEY_HEADER = "X-Internal-Service-Key"
MAX_BODY_BYTES = 16 * 1024

router = APIRouter(prefix="/api/v1/internal", tags=["internal"])

TrimmedName = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)
]
NormalizedPhone = Annotated[str, StringConstraints(pattern=r"^[0-9]{9,15}$")]


class MemberUpsertRequest(BaseModel):
    tenant_name: TrimmedName | None = None
    full_name: TrimmedName
    phone_number: NormalizedPhone = Field(repr=False)
    role: Literal["OWNERMERCHANT", "CASHIER"]
    status: Literal["ACTIVE", "DISABLED"] = "ACTIVE"


class MemberUpsertResponse(BaseModel):
    tenant_id: UUID
    java_user_id: UUID
    status: Literal["ACTIVE", "DISABLED"]


class MemberStore(Protocol):
    async def upsert(
        self, tenant_id: UUID, java_user_id: UUID, member: MemberUpsertRequest
    ) -> None: ...


class TransactionSource(Protocol):
    def transaction(self) -> AbstractAsyncContextManager[AsyncConnection]: ...


class DatabaseMemberStore:
    """Writes members through the shared database connection of the running service."""

    def __init__(self, database: TransactionSource) -> None:
        self.database = database

    async def upsert(
        self, tenant_id: UUID, java_user_id: UUID, member: MemberUpsertRequest
    ) -> None:
        async with self.database.transaction() as connection:
            await upsert_member(
                connection,
                tenant_id,
                java_user_id,
                member.full_name,
                member.phone_number,
                member.role,
                member.status,
                member.tenant_name,
            )


class StoreUnavailable(RuntimeError):
    pass


class UnavailableMemberStore:
    async def upsert(
        self, tenant_id: UUID, java_user_id: UUID, member: MemberUpsertRequest
    ) -> None:
        raise StoreUnavailable("MEMBER_STORE_UNAVAILABLE")


def get_internal_api_settings() -> InternalApiSettings:
    return load_internal_api_settings()


def get_member_store(request: Request) -> MemberStore:
    channel = getattr(request.app.state, "telegram_channel", None)
    database = getattr(channel, "database", None)
    if database is None:
        return UnavailableMemberStore()
    return DatabaseMemberStore(database)


def _json(status_code: int, error: str) -> JSONResponse:
    return JSONResponse({"error": error}, status_code=status_code)


def _authorize(request: Request, settings: InternalApiSettings) -> JSONResponse | None:
    """Fail closed: unset or too short key -> 503; missing or wrong key -> 401."""
    if not settings.configured:
        logger.error("Internal member API rejected: AI_INTERNAL_API_KEY is not configured.")
        return _json(503, "service_unavailable")
    supplied = request.headers.get(KEY_HEADER, "")
    # compare_digest on bytes: constant time and safe for non-ASCII input.
    if not hmac.compare_digest(supplied.encode("utf-8"), settings.api_key.encode("utf-8")):
        logger.warning("Internal member API rejected a request with a missing or wrong key.")
        return _json(401, "unauthorized")
    return None


def _validation_error(fields: list[str]) -> JSONResponse:
    # Field names only: pydantic's default payload would echo the submitted input.
    return JSONResponse(
        {"error": "validation_error", "fields": sorted(set(fields))}, status_code=422
    )


def _parse_uuid(value: str) -> UUID | None:
    try:
        return UUID(value)
    except ValueError:
        return None


def _error_fields(error: ValidationError) -> list[str]:
    return [str(item["loc"][0]) if item["loc"] else "body" for item in error.errors()]


@router.put(
    "/tenants/{tenant_id}/members/{java_user_id}",
    response_model=MemberUpsertResponse,
    summary="Idempotent upsert of one tenant staff member",
)
async def put_member(
    tenant_id: str,
    java_user_id: str,
    request: Request,
    settings: Annotated[InternalApiSettings, Depends(get_internal_api_settings)],
    store: Annotated[MemberStore, Depends(get_member_store)],
) -> JSONResponse | MemberUpsertResponse:
    denied = _authorize(request, settings)
    if denied is not None:
        return denied
    tenant_uuid = _parse_uuid(tenant_id)
    user_uuid = _parse_uuid(java_user_id)
    invalid = [
        name
        for name, value in (("tenant_id", tenant_uuid), ("java_user_id", user_uuid))
        if value is None
    ]
    body = await request.body()
    member: MemberUpsertRequest | None = None
    if len(body) > MAX_BODY_BYTES:
        invalid.append("body")
    else:
        try:
            member = MemberUpsertRequest.model_validate_json(body)
        except ValidationError as error:
            invalid.extend(_error_fields(error))
    if invalid or member is None or tenant_uuid is None or user_uuid is None:
        return _validation_error(invalid or ["body"])
    try:
        await store.upsert(tenant_uuid, user_uuid, member)
    except (SQLAlchemyError, OwnershipLost, StoreUnavailable, TimeoutError) as error:
        logger.error("Internal member upsert failed: %s", type(error).__name__)
        return _json(503, "service_unavailable")
    return MemberUpsertResponse(
        tenant_id=tenant_uuid, java_user_id=user_uuid, status=member.status
    )
