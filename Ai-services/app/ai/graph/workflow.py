"""Workflow assembly and compilation for LangGraph customer service workflow."""

from typing import Any

from langgraph.graph import END, StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.ai.graph.edges import should_continue
from app.ai.graph.nodes import create_agent_node, create_tools_node
from app.ai.graph.state import AgentState
from app.tools import get_default_tool_registry
from app.tools.registry import ToolRegistry


def build_chat_graph(
    openrouter_key: str,
    ai_model: str,
    tools: ToolRegistry | None = None,
    checkpointer: Any | None = None,
) -> CompiledStateGraph:
    """Build and compile the LangGraph chat workflow.

    Args:
        openrouter_key: OpenRouter API key for LLM authentication.
        ai_model: Model name string (e.g. 'google/gemini-2.5-flash').
        tools: ToolRegistry instance containing registered BaseTool tools.
            If None, default ToolRegistry is loaded.
        checkpointer: Optional checkpointer for state persistence.

    Returns:
        CompiledStateGraph ready for ainvoke() execution.
    """
    tool_registry = tools if tools is not None else get_default_tool_registry()

    agent_fn = create_agent_node(
        openrouter_key=openrouter_key,
        ai_model=ai_model,
        tools=tool_registry,
    )
    tools_fn = create_tools_node(tools=tool_registry)

    workflow = StateGraph(AgentState)
    workflow.add_node("agent", agent_fn)
    workflow.add_node("tools", tools_fn)

    workflow.set_entry_point("agent")
    workflow.add_conditional_edges(
        "agent",
        should_continue,
        {
            "tools": "tools",
            END: END,
        },
    )
    workflow.add_edge("tools", "agent")

    return workflow.compile(checkpointer=checkpointer)
