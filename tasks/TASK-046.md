# TASK-046: gRPC Order Status Handler and Mapper

Status: DELEGATED TO SUBAGENT
Memory version: 36

## Task Scope & Acceptance
Implement gRPC handler and mapper for `GetOrderStatus` in Spring Boot:
1. Accept `GetOrderStatusRequest` containing `tenant_id` and `order_number`.
2. Validate non-blank `tenant_id` and `order_number` (return `Status.INVALID_ARGUMENT` if invalid UUID / blank).
3. Query `SalesOrderEntity` via repository matching both `tenant_id` and `order_number`.
4. If order not found, return `Status.NOT_FOUND`.
5. Map entity to Protobuf `GetOrderStatusResponse` (tenant_id, order_number, status, payment_status, total_amount in minor/long units, created_at in ISO-8601 string).
6. Enforce strict tenant isolation: no cross-tenant leakage.

## Exclusive Reserved Writes
- `src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcOrderMapper.java`
- `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierOrderGrpcHandler.java`
- `reports/TASK-046.md`

## Invariants & Rules
- Do NOT modify any other files.
- Named public classes only, no wildcard generics.
- No test execution (tests not run by policy).
- Persist report at `reports/TASK-046.md` upon completion.
