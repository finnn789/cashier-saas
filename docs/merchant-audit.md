# Merchant audit viewer

`GET /api/v1/merchant/outlets/{outletId}/audit-logs` is a read-only owner endpoint. The authenticated merchant token supplies the tenant; the path outlet must be accessible to that owner. Cashiers and records outside that tenant/outlet receive the existing access-denial behavior.

Optional query parameters are `startDate` and `endDate` (`YYYY-MM-DD`), `action`, `actorId`, `entityType`, `page`, and `size`. Dates are interpreted in the outlet time zone. Both dates default to the outlet's current date, and the inclusive range may span at most 366 calendar days. `page` defaults to `0` (maximum `1000000`); `size` defaults to `20` (maximum `100`). The response is ordered by `createdAt` then audit ID descending.

Each entry returns audit identifiers/timestamps, action/entity metadata, the current actor display name when available, reason, and details. It never returns audit entities or actor email, password, session, or credential data. Tenant/global audit records without an outlet are intentionally excluded.
