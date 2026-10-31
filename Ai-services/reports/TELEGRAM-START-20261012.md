# Telegram /start troubleshooting

User reported /start did not trigger a reply after TASK-041. Read-only operational checks: port8001 listens; GET /health/ready returned status ready, mode telegram-polling. Source checks establish that private sender allowlist filtering happens before /start handling.

Safe dotenv metadata showed exactly one allowed ID, equal to documented placeholder123456789. This config rejects the user's actual sender ID; no evidence of a poller startup problem. Orchestrator cleared only the example-only TELEGRAM_ALLOWED_USER_IDS assignment to []; token/key/model and all other dotenv lines retained. No secret or actual sender ID printed. No application code, tests, startup, provider or Telegram API calls performed.

Operator must restart the existing process/F5 because Settings is loaded at startup. Then send /start in private chat. Live /start delivery after restart remains unverified; empty allowlist accepts valid non-bot private senders in this isolated demo. Optional real-ID allowlist can be configured later.
