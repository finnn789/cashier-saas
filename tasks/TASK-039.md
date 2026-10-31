# TASK-039 — AI Service integration contract review

You are the designated read-only architecture/security review worker, not orchestrator or implementation worker. User on 2026-10-11 authorized delegation of Ai-services/PLAN.md. Memory baseline17; previous setup16. Read AGENTS.md, Ai-services/PLAN.md, .ai/MEMORY.md, relevant Spring sources and PRD header decisions. TASK-038 runs concurrently with disjoint reserved paths; review its packet but do not depend on its unfinished implementation. Prior UNIT pause stays binding; no TASK-037 continuation.

Only allowed write: Ai-services/reports/TASK-039.md. Record initial baseline acknowledgement, checkpoint findings and final report. Baseline: reports/artifacts/AI-SERVICES-20261011-DELEGATION/baseline.csv and git-status-before.txt. No application/proto/schema/dependency/canonical edits, nested agents, tests/build/startup/provider/DB calls. Do not read real .env or credentials.

Audit current source with file/line references:
- Existing JWT issuer/audience/HS256/required claims, role and account/merchant scopes; confirm staff JWT is not customer identity.
- PublicOrderService.status, public status capability creation/digest/expiry, controller/DTO and exposed fields; customer persistence/ownership if present. Clearly distinguish available capability from customer phone mapping, which must not be inferred.
- Existing gRPC dependencies/config/server presence; specify minimal proposed read-only messages and auth boundary without claiming it exists or creating proto.
- Trusted phone_number_id -> tenant / wa_id -> customer mapping prerequisites; fail-closed behavior without approved mapping.
- Shared Python downstream contracts for WebSocket auth/events, durable Redis enqueue/dedup/retry/order/ACK/resume, WhatsApp signature/routing/delivery ambiguity. Identify material public/security/schema choices requiring user decision; suggest smallest safe implementable slice without those choices.
- Support-ticket API/persistence absent or present, source-qualified; no write-case expansion.

Deliver concrete dependency order and reserved-path proposals for next workers, distinguishing source evidence, recommendations, unresolved decisions and runtime unverified. No automated tests by policy. Foundation-only progress does not satisfy full PLAN acceptance.
