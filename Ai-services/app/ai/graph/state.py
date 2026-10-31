"""State definition for LangGraph customer service workflow."""

from typing import Annotated
from typing_extensions import TypedDict
from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages


class AgentState(TypedDict, total=False):
    """Dynamic and contextual state passed across LangGraph nodes.

    Attributes:
        messages: Conversation message history with add_messages reducer.
        tenant_id: Tenant UUID string, enforcing strict tenant isolation.
        user_id: Local database user identifier.
        conversation_id: Local database conversation UUID string.
        display_name: Human user's display name or nickname.
        final_response: Plain-text final response ready for delivery.
        error_code: Error identifier if processing encountered an issue.
    """

    messages: Annotated[list[BaseMessage], add_messages]
    tenant_id: str
    user_id: int
    conversation_id: str
    display_name: str
    final_response: str | None
    error_code: str | None
