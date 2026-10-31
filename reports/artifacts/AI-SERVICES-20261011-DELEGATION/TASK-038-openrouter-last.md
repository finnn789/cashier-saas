Foundation implementation is complete and recorded in [TASK-038 report](E:/Java/cashier-saas/Ai-services/reports/TASK-038.md).

Implemented the seven reserved paths only:

- OpenRouter-only `Settings` and explicit runtime validation
- Isolated LangGraph + `ChatOpenRouter` agent with pinned official endpoint, zero retries, timeout/token/step/tool bounds
- Immutable trusted request context and bounded history/replies
- Tenant-scoped, closure-bound FAQ tool with no model-selectable identity
- Indonesian safety prompt preventing unsupported operational claims

Status: **FOUNDATION SUBMITTED FOR SOURCE REVIEW**. Tests, startup, provider/network, Redis, database, and dependency operations were not run by policy.