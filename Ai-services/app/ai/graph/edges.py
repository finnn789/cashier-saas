"""Conditional edge routing for LangGraph customer service workflow."""

import logging
from typing import Literal, cast

from langchain_core.messages import AIMessage
from langgraph.graph import END

from app.ai.graph.state import AgentState

logger = logging.getLogger(__name__)


def should_continue(state: AgentState) -> Literal["tools", "__end__"]:
    """Determine whether to route to tools node or finish the conversation turn.

    If the last message produced by the agent contains one or more tool_calls,
    route to 'tools'. Otherwise, route to END ('__end__').
    """
    messages = state.get("messages", [])
    if not messages:
        logger.info(
            "Graph route decided",
            extra={
                "event": "graph_route_decided",
                "next": "end",
                "tool_calls_count": 0,
                "messages_count": 0,
            },
        )
        return cast(Literal["__end__"], END)

    last_message = messages[-1]
    tool_calls_count = 0
    if isinstance(last_message, AIMessage):
        tool_calls = getattr(last_message, "tool_calls", None)
        tool_calls_count = len(tool_calls) if tool_calls else 0
        if tool_calls and len(tool_calls) > 0:
            logger.info(
                "Graph route decided",
                extra={
                    "event": "graph_route_decided",
                    "next": "tools",
                    "tool_calls_count": tool_calls_count,
                    "messages_count": len(messages),
                },
            )
            return "tools"

    logger.info(
        "Graph route decided",
        extra={
            "event": "graph_route_decided",
            "next": "end",
            "tool_calls_count": tool_calls_count,
            "messages_count": len(messages),
        },
    )
    return cast(Literal["__end__"], END)

