# TASK-058-B2 Report: classification of 2 failing Python unit tests (read-only)

Worker: B2. Baseline: HEAD 89a2ad4; `app/ai/prompts.py`, `app/channels/telegram/sender.py` and both test files are unmodified in the working tree.
Files written: this report only. Commands run: one targeted `unittest` invocation of the two tests (both failed as in the packet), read-only `git log/blame/show`, one small read-only Python snippet that imports `app.ai.prompts` to measure byte sizes. No `.env`, no network, no DB.

Raw result of the targeted run (summary):

```
FAIL: test_maximum_emoji_profile_and_current_keep_complete_newest_pairs
  test_ai.py line 103: Lists differ: [] != [{'role':'user','content':'recent'}, {...'recent-answer'}]
FAIL: test_fatal_api_errors_are_safe_and_terminal (error_type='TelegramBadRequest')
  test_telegram_sender.py line 97: Expected send_message to have been awaited once. Awaited 2 times.
Ran 2 tests, FAILED (failures=2)
```

Git note: both source files and both test files each have exactly ONE commit (e5c64a5, the initial big commit "feat(ai-services): add AI customer-service ..."). So git history cannot show a before/after hunk. The drift is visible only against the earlier snapshots kept in `Ai-services/docs/progress/channels-before/` and `minimal-before/`.

---

## 1. `test_ai.PromptTests.test_maximum_emoji_profile_and_current_keep_complete_newest_pairs`

**Verdict: STALE_TEST** (confidence: medium-high; the one caveat is that this is a design trade-off, see section 5).

### Root cause
- Test: `tests/unit/test_ai.py:96-108`. Builds the worst case: display_name 160 emoji, username 64 emoji, current message 4000 emoji (16000 UTF-8 bytes), history = one huge old pair plus a tiny pair `("recent","recent-answer")`. It asserts the total prompt is `<= MAX_PROMPT_BYTES` (passes) and that `messages[2:-1]` is the tiny recent pair (FAILS: it is `[]`).
- Source: `app/ai/prompts.py:36-52`. `build_prompt` pre-computes a fixed `overhead = len(SYSTEM) + len(profile) + len(current) + 512` and then admits each history pair (newest first) only if `overhead + len(pair)+128 <= MAX_PROMPT_BYTES` (20000).
- Measured with the real module (read-only import):
  - `SYSTEM` = 1144 bytes
  - profile JSON (`ensure_ascii=True`, so each emoji becomes a 12-byte `😀` escape) = 2747 bytes
  - current = 16000 bytes
  - overhead = 1144 + 2747 + 16000 + 512 = **20403**, which is already > 20000 BEFORE any pair is considered. The recent pair would add 19 + 128 = 147. So no pair can ever be selected in this worst case, and `messages[2:-1] == []`.
- Actual content bytes (no reserve) would be 1144+2747+16000 = 19891, plus the 19-byte recent pair = 19910 <= 20000, so the test's expectation is physically achievable; it is only the 512-byte reserve and 128-byte per-pair reserve in the heuristic that exclude it.

### Drift
- `SYSTEM` was rewritten from a ~340-byte generic assistant prompt (`docs/progress/channels-before/app/ai/prompts.py:5-10`, same `MAX_PROMPT_BYTES = 20000`) into the ~1144-byte "Bot Makadai" persona prompt with tool-usage instructions and a Markdown style guide (`app/ai/prompts.py:5-20`). With the old SYSTEM the same worst-case computes overhead 340+2747+16000+512 = ~19599, plus 147 = ~19746 <= 20000, so the pair fit and the test passed (the earlier 74-test pass in `.ai/MEMORY.md` TELEGRAM-UNIT-20261013 predates this). The ~800 extra SYSTEM bytes consumed the ~400 bytes of slack the test relied on.
- Test and `MAX_PROMPT_BYTES` were not updated for the larger SYSTEM.

### Design cross-check
- The new SYSTEM prompt (persona, tools, Markdown rules) is consistent with the Markdown parse_mode default in `sender.py` and the tool-calling graph (`app/ai/graph/nodes.py:16` imports `SYSTEM`). It was clearly an intentional change.
- Searched `.ai/DECISIONS.md`, `.ai/MEMORY.md`, `Ai-services/README.md` for "Makadai", "markdown", "persona", "prompt budget", "MAX_PROMPT": no explicit user-approved decision about the SYSTEM text or prompt-byte budget found. So "intentional" is inferred from code, not cited from a decision.
- Safety: the production behaviour in the failing scenario still keeps the prompt within the 20000 limit and keeps the current message intact; it only drops the newest history pair in an extreme (4000 emoji + max 160/64 emoji profile) input. No security or tenant issue.

### Proposed minimal fix (do not apply)
- Preferred (test-only, `tests/unit/test_ai.py:97`): reduce the "maximum" current message so the worst case again leaves room for one pair, e.g. `current = "😀" * 3800` (15200 bytes: overhead 19603, +147 = 19750 <= 20000). Everything else in the test stays valid (profile equality, single `current`, byte bound).
- Alternative (production, `app/ai/prompts.py:22`): raise `MAX_PROMPT_BYTES` (to about 21000) to restore the original headroom if the user wants the true 4000-char maximum to always retain the newest pair. This changes provider payload bounds and needs a user decision; not recommended without one.

### Not verified
- Did not run the proposed fix. Did not check `MAX_PROMPT_BYTES` against provider limits.

---

## 2. `test_telegram_sender.SenderTests.test_fatal_api_errors_are_safe_and_terminal` (subTest `error_type='TelegramBadRequest'`)

**Verdict: STALE_TEST** (confidence: high).

### Root cause
- Test: `tests/unit/test_telegram_sender.py:89-97`. Loops over `TelegramBadRequest`, `TelegramForbiddenError`, `TelegramUnauthorizedError`; the mocked `bot.send_message` always raises that error. Asserts `(status, code) == ("FAILED","TELEGRAM_REJECTED")`, no secret in `repr(delivery)`, and `bot.send_message.assert_awaited_once()`.
- Source: `app/channels/telegram/sender.py:50-90`. `Sender` defaults to `parse_mode="Markdown"`. When Markdown is on, it catches `TelegramBadRequest` from the first `send_message` (lines 57-66), logs a warning, and RETRIES the send once with `parse_mode=None` (plain text). With a mock that always raises `TelegramBadRequest`, the fallback call raises too, propagates to the outer `except TelegramAPIError` (line ~88), which returns `Delivery("FAILED","TELEGRAM_REJECTED")`.
- So the first two assertions pass (status/code/no secret leaked in the Delivery), and only the call-count assertion fails: 2 awaits instead of 1. Forbidden and Unauthorized pass because they are not `TelegramBadRequest`, so there is no fallback.

### Drift
- The Markdown default plus plain-text fallback is intentional and is encoded in tests in the SAME test file: `test_success_returns_persistable_message_id_with_markdown` (line 33-37, asserts `parse_mode="Markdown"`) and `test_parse_error_falls_back_to_plain_text` (line 39-50: `TelegramBadRequest` first, then success on the second call, expects SENT with message_id 92). These directly contradict "BadRequest is awaited once" for the default Sender. The older snapshot (`docs/progress/channels-before/.../sender.py:45`) had `parse_mode=None` only and a single call; the fatal test was written for that and never updated.
- No decision doc found for Markdown/fallback in `.ai/DECISIONS.md`, `.ai/MEMORY.md`, README. The design intent is evidenced by the two sibling tests and the explicit fallback code (with Indonesian log message), not by a cited decision.

### Consequence assessment
- Not a production bug: a genuinely rejected message is still terminal (`FAILED / TELEGRAM_REJECTED`), the secret does not appear in the returned Delivery, and there is exactly one bounded extra attempt (no loop). Minor note: `logger.error(..., exc_info=True)` logs the aiogram error text/traceback, which includes the Telegram server's message; that is a logging concern separate from this test (the test only asserts on `repr(delivery)`).

### Proposed minimal fix (do not apply; test-only)
In `tests/unit/test_telegram_sender.py:89-97`, replace the unconditional `assert_awaited_once()` with an expectation that depends on the error type: for `TelegramBadRequest` assert `bot.send_message.await_count == 2` (Markdown attempt then plain-text fallback, both rejected), for the other two keep `assert_awaited_once()`. Alternative: construct `Sender(bot, parse_mode=None)` for this test so a BadRequest is not retried (then all three subTests keep `assert_awaited_once()`), but this loses coverage of the default path, so the first option is better. No production change needed.

### Not verified
- Did not run the proposed fix.
