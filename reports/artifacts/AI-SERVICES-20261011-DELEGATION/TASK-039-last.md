TASK-039 contract review is complete and recorded in [TASK-039.md](E:\Java\cashier-saas\Ai-services\reports\TASK-039.md).

Key outcome: integration is not ready to implement. Staff JWTs are not customer identity; public order status uses a separate opaque capability; Spring has no gRPC server/proto; trusted WhatsApp mapping and support-ticket contracts are absent.

The report includes source/line evidence, proposed minimal read-only gRPC messages, fail-closed mapping behavior, durable queue/WebSocket/WhatsApp recommendations, unresolved approval decisions, dependency order, and proposed future reservations.

Only the reserved report was added. No tests, builds, startup, provider, Redis, database, network, or credential reads were performed.