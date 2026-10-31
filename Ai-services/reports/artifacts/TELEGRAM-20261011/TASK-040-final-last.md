Final focused correction completed.

- FAQ loading now performs a bounded binary read of at most 1 MiB plus one byte before decoding/parsing.
- Sanitized Telegram transport exceptions now suppress chained raw HTTPX context with `from None`.
- [TASK-040 report](E:\Java\cashier-saas\Ai-services\reports\TASK-040.md) includes checkpoint 3.

No tests, startup, imports, dependencies, or external calls were run.