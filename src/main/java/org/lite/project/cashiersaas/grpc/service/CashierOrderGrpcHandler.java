package org.lite.project.cashiersaas.grpc.service;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.GetOrderStatusRequest;
import com.cashier.grpc.v1.GetOrderStatusResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.lite.project.cashiersaas.entity.fnb.QSalesOrderEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.grpc.mapper.GrpcOrderMapper;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CashierOrderGrpcHandler extends CashierServiceGrpc.CashierServiceImplBase {

    private static final QSalesOrderEntity ORDER = new QSalesOrderEntity("salesOrder");

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;
    private final GrpcOrderMapper orderMapper;
    private final GrpcTenantAccessPolicy tenantAccessPolicy;

    @Override
    @Transactional(readOnly = true)
    public void getOrderStatus(
            GetOrderStatusRequest request,
            StreamObserver<GetOrderStatusResponse> responseObserver) {
        try {
            if (request == null) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Request cannot be null")
                        .asRuntimeException());
                return;
            }

            UUID tenantUuid = tenantAccessPolicy.requireAllowedTenant(request.getTenantId());

            String orderNumber = request.getOrderNumber();
            if (orderNumber == null || orderNumber.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("order_number must not be blank")
                        .asRuntimeException());
                return;
            }
            orderNumber = orderNumber.trim();

            Optional<SalesOrderEntity> orderOpt = criteriaBuilderFactory
                    .create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                    .where(ORDER.tenant.id.toString()).eq(tenantUuid)
                    .where(ORDER.orderNumber.toString()).eq(orderNumber)
                    .setMaxResults(1)
                    .getResultList()
                    .stream()
                    .findFirst();

            if (orderOpt.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Order not found")
                        .asRuntimeException());
                return;
            }

            SalesOrderEntity order = orderOpt.get();
            GetOrderStatusResponse response = orderMapper.toResponse(order, tenantUuid.toString());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("Failed to retrieve order status for tenant={} cause={}",
                    request != null ? GrpcTenantAccessPolicy.shortTenantRef(request.getTenantId()) : "none",
                    e.getClass().getSimpleName());
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error retrieving order status")
                    .asRuntimeException());
        }
    }
}
