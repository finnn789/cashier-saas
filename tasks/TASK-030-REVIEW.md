# TASK-030 review corrections

Orchestrator source review, memory5. Same allowlist as TASK-030, no new files except updates to reports/TASK-030.md and docs/business-settings.md already reserved. Correct after initial worker finishes; no concurrent worker writes.

1. validateLogoUrl must reject URI userInfo (credentials) and port outside -1 or 1..65535. Never store credentials inside logo URL/audit details. Validate no fetch. Explain in docs.
2. Structured audit JSON currently escapes only newline/carriage-return/tab/quotes/slashes. Encode every U+0000..U+001F control character to valid JSON (e.g. unicode escapes), including backspace/formfeed. Do not add dependency. This prevents invalid JSON for otherwise accepted settings strings.
3. Enforce required names and their post-trim nonblank/column sizes and rates 0..100, <=2 fractional digits within service normalization too; convert invalid rate precision/rounding to InvalidOperationException rather than ArithmeticException 500. Controller validation remains. If already fixed in final initial work, verify and report.
4. Ensure no-op compares numerical rates independent of scale (BigDecimal.compareTo) at named entity settings method, so existing 0 and requested 0.00 do not audit an unchanged value. Persist normal scale on actual changes.

Keep owner guard/tenant lock/currency history guard/atomic audit unchanged. No tests/build/startup/database/network or source outside allowlist. Persist correction checkpoint and exact evidence in report. Do not modify canonical state. Finish READY FOR TESTING after static review.
