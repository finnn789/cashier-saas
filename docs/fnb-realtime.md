# F&B real-time invalidation

F&B operator clients use native WebSocket invalidations as a best-effort, single-JVM optimization. REST list/detail endpoints and polling remain authoritative; always REST-resync after connecting, reconnecting, or a dropped notification.

Request `POST /api/v1/cashier/outlets/{outletId}/fnb/realtime-ticket` with the current Owner/Cashier Bearer JWT. The response provides one random, single-use ticket, `/api/v1/fnb/realtime`, and `cashier-fnb-v1`. The ticket expires after at most 30 seconds (or earlier at JWT expiry). A successful WebSocket connection lasts only until JWT/session/access expiry or revocation, not merely for the ticket lifetime.

Connect without credentials in a URL: `new WebSocket(url, ['cashier-fnb-v1', 'ticket.' + ticket])`. The server requires exactly one application protocol and one ticket protocol, rejects query-string credentials, stores only a ticket digest, and never logs tickets. Client messages are rejected.

Set `app.fnb.realtime.allowed-origins` to a comma-separated list of explicit `http` or `https` origins. Its default is `http://localhost:5173,http://127.0.0.1:5173`; empty, wildcard, malformed, credentialed, or path-based origins are rejected. The same setting governs HTTP CORS for the ticket endpoint and the WebSocket handshake.

Messages contain only `eventId`, `type`, `orderId`, `outletId`, order/payment states, `occurredAt`, and `resyncRequired: true`. No customer, item, or monetary data is sent. Delivery has bounded asynchronous capacity; overload safely drops an invalidation, so clients must continue polling and REST-resync. There is no replay, persistent queue, or cluster fan-out. Periodic maintenance closes expired, revoked, or switched-merchant sessions.
