# TASK-051 — LangGraph Core Architecture & Tools Adapter

## Overview

Establish the LangGraph workflow package under `app/ai/graph/` in `Ai-services`, adding required dependencies (`langgraph`, `langchain-core`, `langchain-openai`) to `pyproject.toml`, defining `AgentState`, wrapping existing `ToolRegistry` tools into LangChain structured tools with secure `tenant_id` context injection, and assembling the compiled `StateGraph`.

---

## Reserved Worker Writes

- `pyproject.toml` (add `langgraph>=0.2.0,<0.3.0`, `langchain-core>=0.3.0,<0.4.0`, `langchain-openai>=0.2.0,<0.3.0`)
- `uv.lock` (reconciled via `uv lock` or `uv sync`)
- `app/ai/graph/__init__.py`
- `app/ai/graph/state.py` (definition of `AgentState` with `add_messages`)
- `app/ai/graph/tools_adapter.py` (adapter wrapping `BaseTool` / `ToolRegistry` to LangChain tools)
- `app/ai/graph/nodes.py` (definitions of `agent_node`, `tools_node`, `error_handler`)
- `app/ai/graph/edges.py` (conditional routing checking `tool_calls`)
- `app/ai/graph/workflow.py` (`build_chat_graph(openrouter_key, ai_model, tool_registry) -> CompiledGraph`)
- `reports/TASK-051.md` (checkpoint report, AST validation, diff, evidence)

No writes permitted to `app/chat/`, `main.py`, `app/application.py`, SQL migrations, or parent Java backend.

---

## Architecture Requirements

1. **State Definition (`app/ai/graph/state.py`)**:
   - `AgentState(TypedDict)` containing:
     - `messages: Annotated[list[BaseMessage], add_messages]`
     - `tenant_id: str` (immutable context)
     - `user_id: int`
     - `conversation_id: str`
     - `display_name: str`
     - `final_response: str | None`
     - `error_code: str | None`

2. **Tools Adapter (`app/ai/graph/tools_adapter.py`)**:
   - Converts `BaseTool` instances from `ToolRegistry` (such as `CatalogSearchTool`, `OrderStatusTool`, `CpuUsageTool`) into LangChain `StructuredTool` instances or LangGraph-compatible tools.
   - Automatically binds `tenant_id` from state into `tool.execute(*, tenant_id=state['tenant_id'], **kwargs)` without exposing `tenant_id` as a model-manipulable argument in the tool JSON schema.

3. **Nodes & Edges (`app/ai/graph/nodes.py`, `edges.py`)**:
   - `agent_node(state)`: Uses `ChatOpenAI(base_url="https://openrouter.ai/api/v1", api_key=..., model=...)` with bound tools. Returns updated messages with `AIMessage`.
   - `tools_node(state)`: Dispatches `tool_calls` safely, catches tool execution exceptions, and returns `ToolMessage` entries.
   - `should_continue(state)`: If the last message contains `tool_calls`, route to `tools_node`; otherwise route to `END`.

4. **Workflow Builder (`app/ai/graph/workflow.py`)**:
   - Assembles `StateGraph(AgentState)`, wires nodes and conditional edges, and compiles the graph:
     `compile()`.
