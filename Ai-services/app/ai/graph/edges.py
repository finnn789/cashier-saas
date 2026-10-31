"""Conditional edge routing for LangGraph customer service workflow."""

from typing import Literal, cast

from langchain_core.messages import AIMessage
from langgraph.graph import END

from app.ai.graph.state import AgentState


def should_continue(state: AgentState) -> Literal["tools", "__end__"]:
    """Determine whether to route to tools node or finish the conversation turn.

    If the last message produced by the agent contains one or more tool_calls,
    route to 'tools'. Otherwise, route to END ('__end__').
    """
    messages = state.get("messages", [])
    if not messages:
        return cast(Literal["__end__"], END)

    last_message = messages[-1]
    if isinstance(last_message, AIMessage):
        tool_calls = getattr(last_message, "tool_calls", None)
        if tool_calls and len(tool_calls) > 0:
            return "tools"

    return cast(Literal["__end__"], END)

