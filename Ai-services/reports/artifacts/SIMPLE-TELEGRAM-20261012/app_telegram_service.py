"""Authorization gate and volatile per-conversation Telegram reply service."""

from __future__ import annotations

from collections import OrderedDict

from app.ai.agent import CustomerServiceAgent
from app.ai.state import ConversationMessage, RequestContext


MAX_CONVERSATIONS = 500
MAX_HISTORY_ITEMS = 10
MAX_HISTORY_CHARS = 2000
HELP_MESSAGE = (
    "Halo. Saya dapat membantu menjawab FAQ yang telah disetujui untuk bisnis ini. "
    "Ketik pertanyaan Anda. Gunakan /reset untuk menghapus riwayat percakapan lokal ini."
)


class TelegramService:
    """Keeps only bounded local history, scoped by configured tenant and Telegram IDs."""

    def __init__(
        self,
        agent: CustomerServiceAgent,
        tenant_id: str,
        allowed_user_ids: list[int],
        max_message_chars: int,
    ) -> None:
        self._agent = agent
        self._tenant_id = tenant_id.strip()
        self._allowed_user_ids = frozenset(allowed_user_ids)
        self._max_message_chars = max_message_chars
        self._history: OrderedDict[str, list[ConversationMessage]] = OrderedDict()

    async def reply_for_update(self, update: dict[str, object]) -> tuple[int, str] | None:
        """Return a delivery target and reply only for authorized ordinary private text."""
        message = update.get("message")
        if not isinstance(message, dict):
            return None
        chat = message.get("chat")
        sender = message.get("from")
        text = message.get("text")
        if not isinstance(chat, dict) or not isinstance(sender, dict) or not isinstance(text, str):
            return None
        chat_id = chat.get("id")
        sender_id = sender.get("id")
        if (
            chat.get("type") != "private"
            or sender.get("is_bot") is not False
            or not _positive_id(chat_id)
            or not _positive_id(sender_id)
            or chat_id != sender_id
            or sender_id not in self._allowed_user_ids
        ):
            return None
        normalized_text = text.strip()
        if not normalized_text or len(normalized_text) > self._max_message_chars:
            return None
        conversation_key = f"{sender_id}:{chat_id}"
        if normalized_text in ("/start", "/help"):
            return chat_id, HELP_MESSAGE
        if normalized_text == "/reset":
            self._history.pop(conversation_key, None)
            return chat_id, "Riwayat percakapan lokal ini sudah dihapus."
        history = tuple(self._history.get(conversation_key, ()))
        context = RequestContext(
            tenant_id=self._tenant_id,
            channel="telegram",
            subject_id=str(sender_id),
            conversation_id=conversation_key,
        )
        reply = await self._agent.respond(context, normalized_text, history)
        self._remember(conversation_key, normalized_text, reply.message)
        return chat_id, reply.message[:4096]

    def _remember(self, conversation_key: str, message: str, reply: str) -> None:
        entries = self._history.pop(conversation_key, [])
        entries.extend(
            [
                ConversationMessage("user", message[:MAX_HISTORY_CHARS]),
                ConversationMessage("assistant", reply[:MAX_HISTORY_CHARS]),
            ]
        )
        self._history[conversation_key] = entries[-MAX_HISTORY_ITEMS:]
        while len(self._history) > MAX_CONVERSATIONS:
            self._history.popitem(last=False)


def _positive_id(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0
