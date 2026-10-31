# TASK-052 — ChatService Wiring to LangGraph & Unit Tests

## Overview

Integrate the compiled LangGraph workflow from `app/ai/graph/` into `ChatService` (`app/chat/service.py`), maintaining short relational PostgreSQL database transactions for message persistence (in `messages` & `conversations`), and authoring unit tests verifying the LangGraph chat execution pipeline offline.

---

## Reserved Worker Writes

- `app/chat/service.py` (adapt `ChatService.handle()` to invoke compiled LangGraph workflow, keeping transactional relational message logging)
- `tests/unit/test_langgraph_chat.py` (unit test suite covering graph execution, tool invocation, and DB logging with mocks)
- `README.md` (documentation update for LangGraph architecture)
- `reports/TASK-052.md` (worker progress, diff, test results, and evidence)

No writes permitted to `app/ai/graph/`, `app/ai/openrouter.py`, `app/core/`, SQL migrations, or parent Java backend.

---

## Architecture Requirements

1. **`ChatService` Adaptation**:
   - In `ChatService.__init__`, receive or construct the compiled LangGraph workflow.
   - In `handle()`, upon receiving a valid incoming `TEXT` message:
     - Transaction 1: Save `USER` message and fetch recent paired history (as `HumanMessage` / `AIMessage`).
     - Invoke LangGraph `ainvoke({"messages": history, "tenant_id": str(tenant_id), ...})`.
     - Transaction 2: Save the resulting final `AIMessage.content` to `public.messages` with role `ASSISTANT`.
     - Return the response string to be delivered via Telegram sender.
   - Maintain error handling for `AIError` / model timeouts / unavailable states.

2. **Unit Tests (`tests/unit/test_langgraph_chat.py`)**:
   - Test offline with mocked LLM (`ChatOpenAI.ainvoke` mock) and mocked database/repository.
   - Test cases:
     1. Basic Q&A without tool call (verifies state transitions and DB saves).
     2. Tool call execution cycle (verifies `tools_node` invocation and two-turn response).
     3. Error handling on model timeout/failure.
