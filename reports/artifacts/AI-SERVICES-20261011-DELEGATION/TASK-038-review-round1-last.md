Corrections complete and recorded in [TASK-038 report](E:/Java/cashier-saas/Ai-services/reports/TASK-038.md).

- Settings are frozen; Pydantic input values are hidden; `load_settings()` returns a fixed sanitized failure.
- Agent construction snapshots and revalidates settings.
- Invalid, unknown, extra-argument, or multiple FAQ tool calls end deterministically without another provider invocation.
- OpenRouter endpoint remains pinned.

Status: **FOUNDATION SUBMITTED FOR SOURCE REVIEW**. No tests, startup, provider, network, DB, or dependency actions were run.