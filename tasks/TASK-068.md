# TASK-068: Mutation check of the stock concurrency tests (TASK-067)

Status: DELEGATED (2026-11-10). Memory version at delegation: 62. Branch `feature/fnb-stock-concurrency-tests` @ `434abe5`. User asked for a mutation check: prove that `FnbStockConcurrencyIntegrationTest` FAILS when a concurrency safeguard is removed.

## How it works (no production file in the repo is edited by a worker or kept modified)
- Worker W1 (haiku) writes MUTATED COPIES of 3 production files into the scratchpad directory `C:\Users\arips\AppData\Local\Temp\claude\b86bedade2d6e9cd\1a275043-4c0d-4192-889c-4c9f74f1bb86\scratchpad\mutants\` — never inside the repo. One mutated file per mutant, each with EXACTLY ONE change and nothing else (keep line endings, imports, everything identical; copy the file byte-for-byte, then apply the single edit).
- The orchestrator copies one mutant over the production file, runs the concurrency test class alone, records killed/survived, then restores the file with `git checkout -- <file>`.

## Mutants (file under `src/main/java/org/lite/project/cashiersaas/service/`; output file name in brackets)
- M1 [`M1_FnbInventoryService.java`] `FnbInventoryService.recordMovement`: remove the pessimistic lock call `inventory.lock(balance);` in the `else` branch (leave an empty `else { }` or delete the else block). Safeguard: balance row lock for manual movements.
- M2 [`M2_FnbStockService.java`] `FnbStockService.consumeForPaidOrder`: remove `inventory.lock(balance);` in the `else` branch. Safeguard: balance row lock during sale deduction.
- M3 [`M3_FnbPaymentService.java`] `FnbPaymentService.pay`: remove ONLY the line `orders.lockTenant(principal.getTenantId());` (the one at ~line 50 of `pay`). Safeguard: per-tenant payment serialisation.
- M4 [`M4_FnbInventoryService.java`] `FnbInventoryService.recordMovement`: disable the fingerprint-conflict check by replacing the condition `!existing.getRequestFingerprint().equals(fingerprint)` with `false`. Safeguard: same key + different payload must conflict.
- M5 [`M5_FnbStockService.java`] `FnbStockService.consumeForPaidOrder`: flip the sign, `balance.apply(delta.negate());` -> `balance.apply(delta);` (control mutant: basic arithmetic).
- M6 [`M6_FnbStockService.java`] `FnbStockService.consumeForPaidOrder`: remove the line `if (inventory.movementByKey(tenantId, key).isPresent()) continue;`. Safeguard: per-(order,item) sale idempotency guard (may be unreachable behind the payment tenant lock; informative).

M1, M4 are two different copies of the same source file (each carries only its own change); likewise M2, M5, M6.

## Rules
- Haiku worker: read the three production files, create the 6 copies, then verify each copy differs from the original by exactly one hunk using a diff command (e.g. `git diff --no-index --stat <original> <mutant>` and show the hunk) and paste the six diffs into `reports/TASK-068-W1.md` (reserved write, plus the six scratchpad files). Touch nothing else. No Maven, no git add/commit/checkout/stash, no edits inside `src/**`.
- Never edit `.ai/*`, `tasks/*`, `postman/**`.
- If a single-line target cannot be found exactly as described, STOP and report instead of guessing.
