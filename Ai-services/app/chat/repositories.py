'''Channel- and tenant-scoped SQLAlchemy operations for minimal chat storage.

One shared bot (channel account) serves many tenants. `ChannelRepository` covers
what exists before a tenant is known (the channel user). `Repository` is the
per-request tenant view (`ChannelRepository.for_tenant`) that owns every
conversation and message query; each of those is bound to the tenant id.
'''

from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import and_, exists, func, insert, or_, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql import Executable
from sqlalchemy.sql.elements import ColumnElement

from app.chat.entities import (
    ChannelAccount,
    ChannelUser,
    Conversation,
    Message,
    TenantMember,
    TenantMembership,
)

Row = dict[str, Any]
CHANNELS = ChannelAccount.__table__
USERS = ChannelUser.__table__
CONVERSATIONS = Conversation.__table__
MESSAGES = Message.__table__
PLATFORMS = frozenset(('TELEGRAM', 'WHATSAPP'))
MEMBERS = TenantMember.__table__
MEMBERSHIPS = TenantMembership.__table__


async def list_telegram_owner_recipients(
    connection: AsyncConnection, tenant_id: UUID
) -> list[tuple[UUID, int]]:
    '''(java_user_id, private chat id) of the tenant's ACTIVE owners with a verified Telegram user.'''
    result = await connection.execute(
        select(MEMBERSHIPS.c.java_user_id, USERS.c.external_user_id)
        .select_from(
            MEMBERSHIPS.join(USERS, USERS.c.id == MEMBERSHIPS.c.user_id).join(
                MEMBERS,
                and_(
                    MEMBERS.c.tenant_id == MEMBERSHIPS.c.tenant_id,
                    MEMBERS.c.java_user_id == MEMBERSHIPS.c.java_user_id,
                ),
            )
        )
        .where(
            MEMBERSHIPS.c.tenant_id == tenant_id,
            MEMBERSHIPS.c.java_user_id.is_not(None),
            MEMBERS.c.role == 'OWNERMERCHANT',
            MEMBERS.c.status == 'ACTIVE',
            USERS.c.platform == 'TELEGRAM',
        )
        .order_by(MEMBERSHIPS.c.java_user_id, USERS.c.external_user_id)
    )
    # A Telegram private chat id equals the user id; skip anything that is not numeric.
    return [
        (row[0], int(row[1])) for row in result.all() if str(row[1]).lstrip('-').isdigit()
    ]


async def register_telegram_channel(
    connection: AsyncConnection,
    external_bot_id: int,
    username: str | None,
) -> UUID:
    '''Provision the shared bot without a tenant and without changing its ownership.'''
    if isinstance(external_bot_id, bool) or not isinstance(external_bot_id, int):
        raise ValueError('TELEGRAM_CHANNEL_INVALID')
    if external_bot_id <= 0:
        raise ValueError('TELEGRAM_CHANNEL_INVALID')
    external_account_id = str(external_bot_id)
    await connection.execute(
        pg_insert(CHANNELS).values(
            id=uuid4(),
            tenant_id=None,
            platform='TELEGRAM',
            external_account_id=external_account_id,
            username=username,
        ).on_conflict_do_nothing(
            index_elements=[CHANNELS.c.platform, CHANNELS.c.external_account_id]
        )
    )
    channel_id = await connection.scalar(
        select(CHANNELS.c.id).where(
            CHANNELS.c.platform == 'TELEGRAM',
            CHANNELS.c.external_account_id == external_account_id,
        )
    )
    if channel_id is None:
        raise RuntimeError('CHANNEL_ACCOUNT_UNAVAILABLE')
    await connection.execute(
        update(CHANNELS).where(
            CHANNELS.c.id == channel_id,
            CHANNELS.c.platform == 'TELEGRAM',
            CHANNELS.c.external_account_id == external_account_id,
        ).values(username=username)
    )
    return channel_id


class ChannelRepository:
    '''Operations of one channel account that exist before a tenant is resolved.'''

    def __init__(self, channel_id: UUID, platform: str) -> None:
        if not isinstance(channel_id, UUID):
            raise ValueError('REPOSITORY_SCOPE_INVALID')
        if platform not in PLATFORMS:
            raise ValueError('REPOSITORY_PLATFORM_INVALID')
        self.channel_id = channel_id
        self.platform = platform

    def for_tenant(self, tenant_id: UUID) -> 'Repository':
        '''The tenant view for one request; the tenant must come from server state.'''
        return Repository(tenant_id, self.channel_id, self.platform)

    async def _first(self, connection: AsyncConnection, statement: Executable) -> Row | None:
        result = await connection.execute(statement)
        mapping = result.mappings().first()
        return dict(mapping) if mapping is not None else None

    def _channel_exists(self) -> ColumnElement[bool]:
        return exists(
            select(CHANNELS.c.id).where(
                CHANNELS.c.id == self.channel_id,
                CHANNELS.c.platform == self.platform,
            )
        )

    async def upsert_user(
        self,
        connection: AsyncConnection,
        external_user_id: str,
        username: str | None,
        first_name: str | None,
    ) -> UUID:
        if not external_user_id or len(external_user_id) > 128:
            raise ValueError('EXTERNAL_USER_ID_INVALID')
        if not await connection.scalar(select(self._channel_exists())):
            raise RuntimeError('CHANNEL_ACCOUNT_UNAVAILABLE')
        statement = pg_insert(USERS).values(
            id=uuid4(),
            platform=self.platform,
            external_user_id=external_user_id,
            username=username,
            first_name=first_name,
        )
        user_id = await connection.scalar(
            statement.on_conflict_do_update(
                index_elements=[USERS.c.platform, USERS.c.external_user_id],
                set_={
                    'username': statement.excluded.username,
                    'first_name': statement.excluded.first_name,
                },
            ).returning(USERS.c.id)
        )
        if user_id is None:
            raise RuntimeError('USER_NOT_RETURNED')
        return user_id


class Repository(ChannelRepository):
    '''Per-request tenant view; every conversation/message statement is tenant-bound.'''

    def __init__(self, tenant_id: UUID, channel_id: UUID, platform: str) -> None:
        if not isinstance(tenant_id, UUID):
            raise ValueError('REPOSITORY_SCOPE_INVALID')
        super().__init__(channel_id, platform)
        self.tenant_id = tenant_id

    def _channel_serves_tenant(self) -> ColumnElement[bool]:
        # A shared channel (NULL tenant) serves every tenant; a dedicated one only its own.
        return or_(CHANNELS.c.tenant_id.is_(None), CHANNELS.c.tenant_id == self.tenant_id)

    def _channel_exists(self) -> ColumnElement[bool]:
        return exists(
            select(CHANNELS.c.id).where(
                CHANNELS.c.id == self.channel_id,
                self._channel_serves_tenant(),
                CHANNELS.c.platform == self.platform,
            )
        )

    def _owned_conversation(
        self, user_id: UUID, external_chat_id: str
    ) -> ColumnElement[bool]:
        return and_(
            self._channel_exists(),
            CONVERSATIONS.c.tenant_id == self.tenant_id,
            CONVERSATIONS.c.channel_id == self.channel_id,
            CONVERSATIONS.c.platform == self.platform,
            CONVERSATIONS.c.user_id == user_id,
            CONVERSATIONS.c.external_chat_id == external_chat_id,
        )

    async def find_duplicate_incoming(
        self,
        connection: AsyncConnection,
        user_id: UUID,
        external_chat_id: str,
        external_message_id: str,
    ) -> bool:
        '''Find a USER provider ID across all sessions of this owner, tenant and chat.'''
        if not external_message_id or len(external_message_id) > 512:
            raise ValueError('EXTERNAL_MESSAGE_ID_INVALID')
        found = await connection.scalar(
            select(MESSAGES.c.id).select_from(
                MESSAGES.join(
                    CONVERSATIONS,
                    MESSAGES.c.conversation_id == CONVERSATIONS.c.id,
                ).join(
                    CHANNELS,
                    and_(
                        CONVERSATIONS.c.channel_id == CHANNELS.c.id,
                        CONVERSATIONS.c.platform == CHANNELS.c.platform,
                    ),
                )
            ).where(
                self._channel_serves_tenant(),
                CONVERSATIONS.c.tenant_id == self.tenant_id,
                CHANNELS.c.id == self.channel_id,
                CHANNELS.c.platform == self.platform,
                CONVERSATIONS.c.user_id == user_id,
                CONVERSATIONS.c.external_chat_id == external_chat_id,
                MESSAGES.c.role == 'USER',
                MESSAGES.c.external_message_id == external_message_id,
            ).limit(1)
        )
        return found is not None

    async def resolve_conversation(
        self,
        connection: AsyncConnection,
        user_id: UUID,
        external_chat_id: str,
        reset: bool = False,
    ) -> UUID:
        if not isinstance(user_id, UUID) or not external_chat_id or len(external_chat_id) > 128:
            raise ValueError('CONVERSATION_OWNER_INVALID')
        if not await connection.scalar(select(self._channel_exists())):
            raise RuntimeError('CHANNEL_ACCOUNT_UNAVAILABLE')
        if reset:
            await connection.execute(
                update(CONVERSATIONS).where(
                    self._owned_conversation(user_id, external_chat_id),
                    CONVERSATIONS.c.status == 'ACTIVE',
                ).values(status='CLOSED')
            )
        conversation_id = await connection.scalar(
            select(CONVERSATIONS.c.id).where(
                self._owned_conversation(user_id, external_chat_id),
                CONVERSATIONS.c.status == 'ACTIVE',
            ).limit(1)
        )
        if conversation_id is not None:
            return conversation_id
        conversation_id = await connection.scalar(
            insert(CONVERSATIONS).values(
                id=uuid4(),
                tenant_id=self.tenant_id,
                channel_id=self.channel_id,
                platform=self.platform,
                user_id=user_id,
                external_chat_id=external_chat_id,
                status='ACTIVE',
            ).returning(CONVERSATIONS.c.id)
        )
        if conversation_id is None:
            raise RuntimeError('CONVERSATION_NOT_RETURNED')
        return conversation_id

    async def insert_message(
        self,
        connection: AsyncConnection,
        conversation_id: UUID,
        user_id: UUID,
        external_chat_id: str,
        role: str,
        content: str,
        external_message_id: str | None = None,
    ) -> int:
        if role not in ('USER', 'ASSISTANT') or not content:
            raise ValueError('MESSAGE_INVALID')
        if role == 'USER' and (not external_message_id or len(external_message_id) > 512):
            raise ValueError('EXTERNAL_MESSAGE_ID_INVALID')
        if role == 'ASSISTANT' and external_message_id is not None:
            raise ValueError('ASSISTANT_EXTERNAL_ID_INVALID')
        owned = await connection.scalar(
            select(CONVERSATIONS.c.id).where(
                CONVERSATIONS.c.id == conversation_id,
                self._owned_conversation(user_id, external_chat_id),
                CONVERSATIONS.c.status == 'ACTIVE',
            )
        )
        if owned is None:
            raise RuntimeError('CONVERSATION_SCOPE_MISMATCH')
        message_id = await connection.scalar(
            insert(MESSAGES).values(
                conversation_id=conversation_id,
                external_message_id=external_message_id,
                role=role,
                content=content,
            ).returning(MESSAGES.c.id)
        )
        if message_id is None:
            raise RuntimeError('MESSAGE_NOT_RETURNED')
        result = await connection.execute(
            update(CONVERSATIONS).where(
                CONVERSATIONS.c.id == conversation_id,
                self._owned_conversation(user_id, external_chat_id),
                CONVERSATIONS.c.status == 'ACTIVE',
            ).values(last_message_at=func.now())
        )
        if result.rowcount != 1:
            raise RuntimeError('CONVERSATION_SCOPE_MISMATCH')
        return int(message_id)

    async def completed_history(
        self,
        connection: AsyncConnection,
        conversation_id: UUID,
        user_id: UUID,
        external_chat_id: str,
        before_message_id: int,
        limit: int = 64,
    ) -> list[tuple[str, str]]:
        '''Pair recent ordered roles; an unmatched USER is replaced by the next USER.'''
        if not 1 <= limit <= 64:
            raise ValueError('HISTORY_LIMIT_INVALID')
        result = await connection.execute(
            select(MESSAGES.c.role, MESSAGES.c.content).select_from(
                MESSAGES.join(
                    CONVERSATIONS,
                    MESSAGES.c.conversation_id == CONVERSATIONS.c.id,
                ).join(
                    CHANNELS,
                    and_(
                        CONVERSATIONS.c.channel_id == CHANNELS.c.id,
                        CONVERSATIONS.c.platform == CHANNELS.c.platform,
                    ),
                )
            ).where(
                self._channel_serves_tenant(),
                CONVERSATIONS.c.tenant_id == self.tenant_id,
                CHANNELS.c.id == self.channel_id,
                CHANNELS.c.platform == self.platform,
                CONVERSATIONS.c.id == conversation_id,
                CONVERSATIONS.c.user_id == user_id,
                CONVERSATIONS.c.external_chat_id == external_chat_id,
                MESSAGES.c.id < before_message_id,
            ).order_by(MESSAGES.c.id.desc()).limit(limit)
        )
        pending_user: str | None = None
        pairs: list[tuple[str, str]] = []
        for role, content in reversed(result.all()):
            if role == 'USER':
                pending_user = content
            elif role == 'ASSISTANT' and pending_user is not None:
                pairs.append((pending_user, content))
                pending_user = None
        return pairs
