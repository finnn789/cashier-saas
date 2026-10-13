# TASK-030: owner business settings

Status: READY FOR TESTING (final source review, memory8). Memory baseline 5 retained. User explicitly requested implementation/delegation on 2026-10-07. Actual executor: authenticated Codex CLI, Ruflo coordination only. Review: reports/MVP-20261007-orchestrator.md.

Read AGENTS.md, .ai/MEMORY.md, PRD-MVP.md active header, .agents/skills/vertical-slice-code-pattern/SKILL.md and `.ai/context/settings-audit-realtime-20261007.md`. PROJECT_MEMORY missing/deleted; do not restore. Latest user worker instruction supersedes skill no-agent text. You are implementation worker, not orchestrator. Implement TASK-030 exactly to reviewed brief, no new product decisions.

Acceptance: GET/PUT settings require owner/current outlet; validate all fields; persist tenant/outlet in one transaction; lock/guard; future-order rates only; reject currency change after any tenant order/sale; changed update audited atomically; no-op not audited; named null-free DTO and mapper; preserve dirty code. No migrations required.

Exclusive writes (paths below relative to src/main/java/org/lite/project/cashiersaas except docs/report):
- controller/MerchantBusinessSettingsController.java
- service/MerchantBusinessSettingsService.java
- repository/MerchantBusinessSettingsRepository.java
- dto/MerchantBusinessSettingsMapper.java
- dto/request/MerchantBusinessSettingsRequest.java
- dto/response/MerchantBusinessSettingsResponse.java
- entity/shared/merchant/TenantEntity.java
- entity/shared/merchant/OutletEntity.java
- entity/shared/audit/AuditLogEntity.java (only add BUSINESS_SETTINGS_CHANGED action; preserve prior changes)
- docs/business-settings.md
- reports/TASK-030.md

No other writes. Baseline hashes: reports/artifacts/MVP-20261007/baseline-sha256.csv; git status baseline adjacent. Shared read APIs MerchantAccessGuard, FnbOrderRepository.lockTenant, AuditLogRepository.persist. New repository handles only currency-use detection via Blaze. Do not modify shared repositories/operational services. Other workers own audit viewer/realtime.

Before implementation persist reports/TASK-030.md acknowledgement of memory5, dirty baseline, reservations and acceptance plan. After each meaningful checkpoint update report changed files/progress/static evidence/tests not run/remaining. Final report exact files, signatures, validation, authorization/currency/rate audit behavior, unresolved risks and READY FOR TESTING if complete. No tests/build/startup/database/network commands, nested agents, canonical writes, git mutation, reading .env/secrets. Static file inspection only.
