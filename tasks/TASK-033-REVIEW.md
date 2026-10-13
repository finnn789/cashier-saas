# TASK-033 review corrections

Same exact TASK-033 allowlist/baseline8; canonical memory9 only records coordination. Initial submission not accepted. No production/Maven/test execution/canonical writes. Persist correction checkpoints in reports/TASK-033.md.

1. Both tests use Jackson2 com.fasterxml.jackson.databind, but project Boot4 active mapper is tools.jackson.databind Jackson3. Replace imports/use actual Jackson3. Do not add dependencies. Verify API from cached jar/source if unsure.
2. No-op test is incorrect: fixture has address/phone/outletAddress/outletPhone populated, validRequest omits all, so PUT clears them and legitimately emits audit. Construct truly identical request for no-op, changing only BigDecimal scale. Never change production or assert away legitimate audit.
3. Parseable-audit test currently contains no control chars in serialized values. Add changed business/address value with U+0000, backspace/formfeed/newline/tab, quotes and backslash; parse emitted before/after JSON with Jackson3 and assert exact roundtrip and no raw control leakage. Continue credential redaction checks.
4. Add currency change with no history success+audit and unchanged currency bypass checks, owner read denial, valid inclusive rates0/100 and invalid null/over100/excess scale/null/overlength names/logoport0 where useful. Parameterized tests preferred for independent validation cases; avoid one large loop that hides subsequent cases. Assert audit Action and that optional nonnull mapper values are translated; null omission independently verified.

Do not claim no production defects until runtime tests pass. Finish READY FOR TEST EXECUTION with exact cases and unrun status.
