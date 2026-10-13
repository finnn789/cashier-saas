# Read-only architecture reviewer: F&B operations and persistence

Assigned by orchestrator on 2026-10-07. Canonical baseline memory version: 2.
Ruflo swarm: swarm-1791361273309-rota2f.
Execution: authenticated Codex CLI, independent fresh context. This is not Ruflo agent_execute.

Read AGENTS.md, .ai/MEMORY.md, .ai/ARCHITECTURE.md and .codex/agents/mvp_architect.toml once. Current instructions override historical template details. PROJECT_MEMORY.md is deleted in the dirty working tree; do not restore it.

Review PublicOrderService, FnbPosOrderService, FnbOrderPricingService, FnbPaymentService, MockFnbGatewayService, FnbKitchenService, FnbSalesReportRepository, FnbOrderRepository and SalesOrderEntity. Explain actual Pay First transitions, snapshot pricing, idempotency, locking, audit and report boundaries. Inspect current migration filenames and non-secret Flyway/Liquibase/JPA settings only. Identify material gaps in .ai/ARCHITECTURE.md and limits of source evidence. Do not read .env, auth.json, secrets, datasource credentials or liquibase-diff.properties. PRD text is older than active Pay First/three-role policy; do not invent new product decisions.

Allowlist: read-only source/configuration inspection, git status/diff, final review response. No filesystem writes, builds/tests/startup/database/network operations, nested agents or MCP mutation, canonical updates, migration repair or implementation. Existing application changes and staged migration deletions are user work and must be preserved. The host persists final response only to reports/RUFLO-ARCH-FNB.md.

Return a concise report: memory baseline; files inspected; architecture findings; corrections or unknowns; testing not run by policy; changed files none. Distinguish source observations from proposed improvements. Do not certify end-to-end readiness.
