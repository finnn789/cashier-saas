Implemented and documented the three new source-review corrections in [TASK-038 report](E:/Java/cashier-saas/Ai-services/reports/TASK-038.md):

- `load_settings()` now also catches `SettingsError`.
- Parsed `invalid_tool_calls` route to terminal rejection before normal tool-call handling.
- Every model turn passes `retries=None`; installed integration source confirms invocation kwargs reach `send_async`.

Only reserved files changed. No tests, imports, startup, provider/network, DB, or dependency operations were run.