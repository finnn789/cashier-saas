'''Owned polling with public startup observation and native graceful teardown.'''

import asyncio
import logging
from contextlib import suppress
from typing import Any

from aiogram import Bot, Dispatcher
from aiogram.utils.token import TokenValidationError
from sqlalchemy.exc import SQLAlchemyError

from app.ai.openrouter import OpenRouterClient
from app.channels.telegram.handlers import Intake
from app.channels.telegram.sender import Sender
from app.chat.members import upsert_tenant
from app.chat.repositories import ChannelRepository, register_telegram_channel
from app.chat.service import ChatService
from app.config import Settings
from app.core.database import Database, OwnershipLost
from app.tools import get_default_tool_registry

logger = logging.getLogger(__name__)


async def await_owned(task: asyncio.Task[None]) -> None:
    '''Finish an owned task before propagating cancellation of its caller.'''
    cancelled = False
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            cancelled = True
    task.result()
    if cancelled:
        raise asyncio.CancelledError


class TelegramChannel:
    def __init__(
        self,
        bot: Bot,
        dispatcher: Dispatcher,
        database: Database,
        intake: Intake,
    ) -> None:
        self.bot = bot
        self.dispatcher = dispatcher
        self.database = database
        self.intake = intake
        self.polling_task: asyncio.Task[None] | None = None
        self.supervisor_task: asyncio.Task[None] | None = None
        self.poller_stop_task: asyncio.Task[None] | None = None
        self.cleanup_task: asyncio.Task[None] | None = None
        self.polling_started = asyncio.Event()
        self.polling_finished = asyncio.Event()
        self.failed = False
        self.closing = False
        self.dispatcher.startup.register(self.observe_startup)

    async def observe_startup(self, bot: Bot, **kwargs: Any) -> None:
        # start_polling has initialized its public stop signals before this call.
        self.polling_started.set()
        logger.info(
            'Polling started, waiting for Telegram updates',
            extra={'event': 'bot_polling_started'},
        )

    async def run_poller(self) -> None:
        logger.info('Polling loop starting', extra={'event': 'bot_polling_loop_starting'})
        try:
            await self.dispatcher.start_polling(
                self.bot,
                handle_as_tasks=False,
                handle_signals=False,
                close_bot_session=False,
                allowed_updates=['message'],
            )
        finally:
            self.polling_finished.set()
            logger.info('Polling loop finished', extra={'event': 'bot_polling_loop_finished'})

    def start(self) -> None:
        self.polling_task = asyncio.create_task(
            self.run_poller(), name='telegram-polling'
        )
        self.supervisor_task = asyncio.create_task(
            self.supervise(), name='telegram-supervisor'
        )

    async def supervise(self) -> None:
        failure_waiter = asyncio.create_task(
            self.intake.failed.wait(), name='intake-failure-wait'
        )
        reason = 'unspecified'
        try:
            if self.polling_task is None:
                raise RuntimeError('CHANNEL_TASKS_MISSING')
            logger.info('Supervisor started', extra={'event': 'bot_supervisor_started'})
            while not self.closing:
                done, _ = await asyncio.wait(
                    [self.polling_task, failure_waiter],
                    timeout=1,
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if done:
                    self.failed = True
                    if self.polling_task in done:
                        error = (
                            None
                            if self.polling_task.cancelled()
                            else self.polling_task.exception()
                        )
                        reason = 'polling task ended' + (
                            f' error_type={type(error).__name__}' if error else ''
                        )
                    else:
                        reason = 'intake reported a fatal handler failure'
                    break
                try:
                    await self.database.ping()
                except (SQLAlchemyError, OwnershipLost, asyncio.TimeoutError) as error:
                    self.failed = True
                    reason = f'database check failed error_type={type(error).__name__}'
                    break
            if self.failed:
                # Only types are logged: SDK/database messages can carry secrets.
                logger.error(
                    'Telegram channel stopped after a supervised failure',
                    extra={'event': 'bot_supervised_failure', 'reason': reason},
                )
                await self.stop_poller()
        finally:
            failure_waiter.cancel()
            with suppress(asyncio.CancelledError):
                await failure_waiter

    async def graceful_stop_poller(self) -> None:
        runner = self.polling_task
        if runner is None:
            return
        startup_waiter = asyncio.create_task(
            self.polling_started.wait(), name='polling-startup-wait'
        )
        try:
            await asyncio.wait(
                [startup_waiter, runner],
                return_when=asyncio.FIRST_COMPLETED,
            )
        finally:
            startup_waiter.cancel()
            with suppress(asyncio.CancelledError):
                await startup_waiter

        if self.polling_started.is_set() and not self.polling_finished.is_set():
            stop_request = asyncio.create_task(
                self.dispatcher.stop_polling(), name='dispatcher-stop-request'
            )
            try:
                await asyncio.wait(
                    [stop_request, runner],
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if stop_request.done():
                    try:
                        stop_request.result()
                    except RuntimeError:
                        if not self.polling_finished.is_set():
                            raise
            finally:
                if not stop_request.done():
                    stop_request.cancel()
                with suppress(asyncio.CancelledError, RuntimeError):
                    await stop_request

        with suppress(asyncio.CancelledError, Exception):
            await runner

    async def stop_poller(self) -> None:
        if self.poller_stop_task is None:
            self.poller_stop_task = asyncio.create_task(
                self.graceful_stop_poller(), name='telegram-graceful-stop'
            )
        await await_owned(self.poller_stop_task)

    async def ready(self) -> bool:
        if (
            self.closing
            or self.failed
            or not self.database.owned
            or not self.intake.healthy
            or not self.polling_started.is_set()
            or self.polling_finished.is_set()
        ):
            return False
        if self.polling_task is None or self.supervisor_task is None:
            return False
        if self.polling_task.done() or self.supervisor_task.done():
            return False
        try:
            return await self.database.ping()
        except (SQLAlchemyError, OwnershipLost, asyncio.TimeoutError):
            return False

    async def stop(self) -> None:
        if self.cleanup_task is None:
            self.cleanup_task = asyncio.create_task(
                self.cleanup(), name='telegram-cleanup'
            )
        await await_owned(self.cleanup_task)

    async def cleanup(self) -> None:
        self.closing = True
        logger.info('Shutting down Telegram channel', extra={'event': 'bot_shutting_down'})
        try:
            if self.supervisor_task is not None:
                self.supervisor_task.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await self.supervisor_task
            await self.stop_poller()
        finally:
            await close_startup_resources(self.bot, self.database)


async def start_telegram_channel(
    settings: Settings, ai: Any | None = None
) -> TelegramChannel:
    # SDK exception payloads can contain tokens or connection details. WARNING keeps polling
    # failures (network loss, 409 conflict, bad token) visible while routine INFO stays quiet;
    # RedactionFilter (app.observability) masks bot-token patterns that may appear in them.
    logging.getLogger('aiogram').setLevel(logging.WARNING)
    logging.getLogger('sqlalchemy.engine').disabled = True
    if not settings.telegram.token:
        raise RuntimeError('Isi TELEGRAM_BOT_TOKEN.')
    try:
        bot = Bot(settings.telegram.token)
    except TokenValidationError:
        raise RuntimeError('Format TELEGRAM_BOT_TOKEN tidak valid.') from None
    database = Database(settings.database)
    channel: TelegramChannel | None = None
    try:
        logger.info(
            'Authenticating bot with Telegram', extra={'event': 'startup_authenticating'}
        )
        profile = await bot.get_me()
        logger.info(
            'Bot authenticated',
            extra={
                'event': 'startup_bot_authenticated',
                'bot_id': profile.id,
                'bot_username': profile.username,
            },
        )
        webhook = await bot.get_webhook_info()
        if webhook.url:
            raise RuntimeError('Webhook aktif; operator harus menghapusnya sebelum polling.')
        logger.info(
            'No webhook active, polling is allowed', extra={'event': 'startup_no_webhook'}
        )
        await database.acquire(bot.id)
        logger.info('Database ownership acquired', extra={'event': 'startup_database_acquired'})
        fallback_tenant_id = settings.telegram.fallback_tenant_id
        async with database.transaction() as connection:
            # One shared bot for every tenant: registered without a tenant.
            channel_id = await register_telegram_channel(
                connection, profile.id, profile.username
            )
            if fallback_tenant_id is not None:
                # Conversations reference tenants; make the fallback tenant row exist.
                await upsert_tenant(connection, fallback_tenant_id, None)
        logger.info(
            'Telegram channel registered',
            extra={'event': 'startup_channel_registered', 'channel_id': channel_id},
        )
        if fallback_tenant_id is not None:
            logger.warning(
                'AI_TENANT_ID development fallback is active for unverified Telegram users.',
                extra={'event': 'startup_tenant_fallback_active'},
            )
        channel_repository = ChannelRepository(channel_id, 'TELEGRAM')
        tools = get_default_tool_registry()
        chat = ChatService(
            database=database,
            channel_repository=channel_repository,
            ai=ai,
            tools=tools,
            ai_settings=settings.ai,
            fallback_tenant_id=fallback_tenant_id,
        )
        logger.info(
            'Chat service ready',
            extra={
                'event': 'startup_chat_service_ready',
                'ai_graph': getattr(chat, 'graph', None) is not None,
                'allowlist_size': len(getattr(settings.telegram, 'allowed_user_ids', None) or ()),
            },
        )
        intake = Intake(settings.telegram, chat, Sender(bot))
        dispatcher = Dispatcher(disable_fsm=True)
        dispatcher.message.register(intake.handle)
        channel = TelegramChannel(bot, dispatcher, database, intake)
        channel.start()
        await asyncio.sleep(0)
        logger.info('Telegram channel started', extra={'event': 'startup_channel_started'})
        return channel
    except BaseException as error:
        if not isinstance(error, asyncio.CancelledError):
            logger.error(
                'Telegram channel startup failed',
                extra={'event': 'startup_channel_failed', 'error_type': type(error).__name__},
            )
        if channel is not None:
            await channel.stop()
        else:
            cleanup = asyncio.create_task(
                close_startup_resources(bot, database), name='startup-cleanup'
            )
            await await_owned(cleanup)
        if isinstance(error, asyncio.CancelledError):
            raise
        if isinstance(error, RuntimeError):
            raise
        raise RuntimeError(
            'Startup Telegram/database gagal; periksa konfigurasi dan konektivitas.'
        ) from None


async def close_startup_resources(bot: Bot, database: Database) -> None:
    try:
        await bot.session.close()
    finally:
        await database.close()
