# TASK-032: after-commit F&B WebSocket invalidation

Status: READY FOR TESTING (final source review, memory8). Memory baseline 5 retained. User requested delegated implementation on 2026-10-07. Worker via authenticated Codex CLI; Ruflo registrations are coordination only. Review: reports/MVP-20261007-orchestrator.md.

Read AGENTS.md, .ai/MEMORY.md, PRD active header, vertical-slice-code-pattern skill and `.ai/context/settings-audit-realtime-20261007.md`. Implement native WebSocket ticket design there, not STOMP. Worker not orchestrator; missing PROJECT_MEMORY must not be restored.

Acceptance: scoped Owner/Cashier ticket HTTP API; 30s/once cryptographic ticket bounded storage; JWT decoder expiry and principal match; ticket in websocket subprotocol header, never URL/log; authenticated guarded handshake and current scoped delivery; explicit origins; expiration/revocation cleanup; bounded sessions/tickets/writes; no client mutation; minimal JSON snapshots only after commit; all QR/POS/payment/mock/discount/cancel/kitchen hooks; idempotent replay emits none; errors after commit do not fail already committed requests; REST fallback unchanged. No persistent schema.

Exclusive writes relative to src/main/java/org/lite/project/cashiersaas:
- configuration/FnbWebSocketConfiguration.java
- component/FnbRealtimeHandshakeInterceptor.java
- component/FnbRealtimeWebSocketHandler.java
- component/FnbOrderChangedEvent.java
- component/FnbOrderChangedListener.java
- service/FnbRealtimeTicketService.java
- service/FnbRealtimeAccessService.java
- service/FnbRealtimePublisher.java
- controller/FnbRealtimeController.java
- dto/response/FnbRealtimeTicketResponse.java
- dto/response/FnbRealtimeEventResponse.java
- dto/FnbRealtimeMapper.java
- service/PublicOrderService.java
- service/FnbPosOrderService.java
- service/FnbPaymentService.java
- service/MockFnbGatewayService.java
- service/FnbDiscountService.java
- service/FnbOrderCancellationService.java
- service/FnbKitchenService.java
- configuration/SecurityConfiguration.java (only exact websocket handshake route and explicit origins if needed; preserve other rules)
- pom.xml (only Boot-managed websocket dependency)
- docs/fnb-realtime.md
- reports/TASK-032.md

No other writes, no entity/schema/test/shared principal changes. Use existing JwtDecoder and converter; inspect actual converter Authentication type. Implementation helper inner classes allowed within reserved files. Framework-only override wildcards isolate/document exact necessity before introducing. Single-JVM best-effort transport documented. docs example ticket REST, WebSocket subprotocol, reconnect/resync. Injection must avoid cycles; publisher only depends ApplicationEventPublisher, listener separate.

Baseline hashes reports/artifacts/MVP-20261007/baseline-sha256.csv. Acknowledge memory5/baseline/reservation/brief in report before code; checkpoints mandatory. Final report actual changed files/acceptance evidence/exact contracts/framework limitation/testing not run/remaining. No tests/build/startup/database/network commands, nested agents, canonical changes, git mutation or secret reads. Preserve dirty code. If missing dependency/API certainty, report narrow issue; do not invent stub.
