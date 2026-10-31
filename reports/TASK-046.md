# TASK-046 Worker Report: gRPC Order Status Handler and Mapper

## Checkpoint 0 — Before Implementation

- Acknowledged memory version 36 and `tasks/TASK-046.md` reservation.
- Scope: Implement gRPC order status handler (`CashierOrderGrpcHandler`) and mapper (`GrpcOrderMapper`) in Spring Boot for the `GetOrderStatus` RPC defined in `src/main/proto/cashier_service.proto`.
- Exclusive reserved write paths:
  - `src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcOrderMapper.java`
  - `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierOrderGrpcHandler.java`
  - `reports/TASK-046.md`
- Codebase discovery:
  - Reviewed `SalesOrderEntity` and its metadata `QSalesOrderEntity`.
  - Inspected `FnbOrderRepository` and Blaze-Persistence query patterns.
  - Inspected generated Protobuf and gRPC stubs under `com.cashier.grpc.v1.*`.
  - Inspected Python client invocation patterns in `Ai-services/app/integrations/grpc/client.py` and `Ai-services/app/tools/order.py`.
- Acceptance criteria and invariants:
  1. Validate non-blank `tenant_id` and parse UUID; validate non-blank `order_number`. Return `Status.INVALID_ARGUMENT` on failure.
  2. Query `SalesOrderEntity` with strict tenant isolation (`tenant.id == tenantUuid` AND `orderNumber == orderNumber`).
  3. Return `Status.NOT_FOUND` if order is not found under the specified tenant.
  4. Map entity to Protobuf `GetOrderStatusResponse` (`tenant_id`, `order_number`, `status`, `payment_status`, `total_amount` in minor/long units, `created_at` in ISO-8601 string).
  5. Named public classes only, no wildcard generics.
  6. Testing not run by policy.

---

## Checkpoint 1 — Implementation Details

### 1. `GrpcOrderMapper`
- Location: `src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcOrderMapper.java`
- Package: `org.lite.project.cashiersaas.grpc.mapper`
- Key design aspects:
  - Spring `@Component` with null-safe mapping methods.
  - `toResponse(SalesOrderEntity order)` and `toResponse(SalesOrderEntity order, String fallbackTenantId)`.
  - Returns `GetOrderStatusResponse.getDefaultInstance()` when `order == null`.
  - Extracts tenant ID from `order.getTenant().getId().toString()`, with fallback to validated tenant UUID string.
  - Maps `orderNumber` (`order.getOrderNumber()`).
  - Maps `status` from `OrderStatus` enum name (`order.getStatus().name()`).
  - Maps `paymentStatus` from `PaymentStatus` enum name (`order.getPaymentStatus().name()`).
  - Maps `totalAmount` to `long` from `BigDecimal` via `order.getTotal().longValue()` (or `0L`).
  - Formats `createdAt` into ISO-8601 instant string via `DateTimeFormatter.ISO_INSTANT.format(order.getCreatedAt())`.

### 2. `CashierOrderGrpcHandler`
- Location: `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierOrderGrpcHandler.java`
- Package: `org.lite.project.cashiersaas.grpc.service`
- Key design aspects:
  - Annotated with `@Slf4j`, `@GrpcService`, and `@RequiredArgsConstructor`.
  - Extends `CashierServiceGrpc.CashierServiceImplBase`.
  - Injects `EntityManager`, `CriteriaBuilderFactory`, and `GrpcOrderMapper`.
  - Implements unary RPC `getOrderStatus(GetOrderStatusRequest, StreamObserver<GetOrderStatusResponse>)` with `@Transactional(readOnly = true)`.
  - Validation steps:
    - Null request check -> `Status.INVALID_ARGUMENT.withDescription("Request cannot be null")`.
    - Null or blank `tenant_id` -> `Status.INVALID_ARGUMENT.withDescription("tenant_id must not be blank")`.
    - Invalid UUID format for `tenant_id` -> `Status.INVALID_ARGUMENT.withDescription("tenant_id must be a valid UUID: ...")`.
    - Null or blank `order_number` -> `Status.INVALID_ARGUMENT.withDescription("order_number must not be blank")`.
  - Persistence query & tenant isolation:
    - Executes Blaze-Persistence query on `SalesOrderEntity` filtering on both `ORDER.tenant.id == tenantUuid` and `ORDER.orderNumber == orderNumber` with `setMaxResults(1)`.
    - Strict isolation: if an order exists under another tenant, the query returns empty, leaking no existence information across tenant boundaries.
  - Error responses:
    - If order not found, returns `Status.NOT_FOUND.withDescription("Order not found with order_number: ...")`.
    - Enclosing `try-catch` catches any unexpected runtime exception, logs the error, and returns `Status.INTERNAL` so gRPC clients never hang.
  - Successful response:
    - Calls `orderMapper.toResponse(order, tenantUuid.toString())`.
    - Calls `responseObserver.onNext(response)` followed by `responseObserver.onCompleted()`.

---

## Checkpoint 2 — Review & Static Verification

Status: **READY FOR TESTING**

### Verification Summary
- **Reserved write adherence**: Only the 3 reserved files were written:
  - `src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcOrderMapper.java`
  - `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierOrderGrpcHandler.java`
  - `reports/TASK-046.md`
- **Tenant isolation**: Enforced via composite predicate `tenant.id = tenantUuid AND orderNumber = orderNumber`. No cross-tenant data leak is possible.
- **Protobuf / gRPC compatibility**:
  - Request and response types match `src/main/proto/cashier_service.proto` and generated classes in `com.cashier.grpc.v1`.
  - Total amount mapped as `int64` minor/long units.
  - Created at mapped as ISO-8601 string.
- **Rule compliance**:
  - Named public classes only.
  - Zero wildcard generics (`<?>`, `? extends`, `? super`).
  - No test execution (testing not run by policy).

### Recommended Verification Scenarios for User Testing Phase
1. `getOrderStatus` with blank `tenant_id` -> returns `Status.INVALID_ARGUMENT`.
2. `getOrderStatus` with invalid UUID `tenant_id` -> returns `Status.INVALID_ARGUMENT`.
3. `getOrderStatus` with blank `order_number` -> returns `Status.INVALID_ARGUMENT`.
4. `getOrderStatus` with non-existent `order_number` -> returns `Status.NOT_FOUND`.
5. `getOrderStatus` with `order_number` belonging to a different tenant -> returns `Status.NOT_FOUND` (tenant isolation test).
6. `getOrderStatus` with valid matching tenant and order -> returns `Status.OK` with correct `tenant_id`, `order_number`, `status`, `payment_status`, `total_amount`, and `created_at`.
