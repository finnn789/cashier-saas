# Explicit project decisions

The PRD and explicit user instructions remain authoritative; this file records decisions captured during AI Services work.

## AI Services schema — 2026-10-13

- Latest user choice separates Telegram and WhatsApp identities; earlier transient linking answer is superseded.
- User confirms AI database is empty and authorizes replacing the initial schema snapshot. This does not execute/reset any live database.
- User explicitly selects the most minimal schema and defers durable queues. Inbox/outbox/background queue worker are excluded from current scope; core conversation/history remain.
- Current task prepares schema for both platforms and adapts existing Telegram transport. WhatsApp transport/account verification/business access remain subsequent work.
