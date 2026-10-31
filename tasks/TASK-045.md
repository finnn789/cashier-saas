# TASK-045: gRPC Catalog Item Handler and Mapper

Status: DELEGATED TO SUBAGENT
Memory version: 36

## Task Scope & Acceptance
Implement gRPC handler and mapper for `GetCatalogItem` in Spring Boot:
1. Accept `GetCatalogItemRequest` containing `tenant_id`, `query`, and `limit`.
2. Validate non-blank `tenant_id` (return `Status.INVALID_ARGUMENT` if invalid UUID / blank).
3. Query products using repository / entity matching `tenant_id` and active availability.
4. Convert entities to Protobuf `CatalogItem` messages (id, name, price, is_available, category).
5. Build and return `GetCatalogItemResponse`.
6. Enforce strict tenant isolation: no cross-tenant leakage.

## Exclusive Reserved Writes
- `src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcCatalogMapper.java`
- `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierCatalogGrpcHandler.java`
- `reports/TASK-045.md`

## Invariants & Rules
- Do NOT modify any other files.
- Named public classes only, no wildcard generics.
- No test execution (tests not run by policy).
- Persist report at `reports/TASK-045.md` upon completion.
