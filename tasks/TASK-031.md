# TASK-031: owner audit viewer

Status: READY FOR TESTING (final source review, memory8). Memory baseline 5 retained. User explicitly requested implementation/delegation on 2026-10-07. Actual executor authenticated Codex CLI. Review: reports/MVP-20261007-orchestrator.md.

Read AGENTS.md, .ai/MEMORY.md, active PRD header, vertical-slice-code-pattern skill, `.ai/context/settings-audit-realtime-20261007.md`. Implement TASK-031 contract only. Missing PROJECT_MEMORY must not be restored. Worker not orchestrator.

Acceptance: owner-only scoped GET audit logs; tenant AND outlet filter including count; no null-outlet rows; bounded pagination/filter lengths/date range; outlet-local date boundaries; stable createdAt/id descending; explicit mapper; named public null-free DTOs; no secrets/entity leak. No mutable audit endpoint.

Exclusive writes relative to src/main/java/org/lite/project/cashiersaas:
- controller/MerchantAuditController.java
- service/MerchantAuditService.java
- repository/MerchantAuditRepository.java
- dto/MerchantAuditMapper.java
- dto/request/MerchantAuditFilter.java
- dto/response/MerchantAuditEntryResponse.java
- dto/response/MerchantAuditPageResponse.java
- docs/merchant-audit.md
- reports/TASK-031.md

No writes to AuditLogRepository/AuditLogEntity/tenant/outlet/security/pom or any other path. Other workers own those. Existing actor display field must be inspected before choosing projection; no schema change. Use Blaze-Persistence queries, QueryDSL metadata only if already generated. Baseline hashes reports/artifacts/MVP-20261007/baseline-sha256.csv.

Persist report first with memory5/baseline/reservation/acceptance plan, and after each checkpoint with files/evidence/remaining. Final READY FOR TESTING only if implemented; report exact signatures, filtering and authorization semantics plus recommended cases. No tests/build/startup/database/network, nested agents, canonical edits, git mutation or secret reads. Preserve all dirty work.
