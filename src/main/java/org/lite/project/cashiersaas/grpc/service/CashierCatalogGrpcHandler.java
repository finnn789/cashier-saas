package org.lite.project.cashiersaas.grpc.service;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.grpc.mapper.GrpcCatalogMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CashierCatalogGrpcHandler extends CashierServiceGrpc.CashierServiceImplBase {

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;
    private final GrpcCatalogMapper catalogMapper;

    @Override
    @Transactional(readOnly = true)
    public void getCatalogItem(
            GetCatalogItemRequest request,
            StreamObserver<GetCatalogItemResponse> responseObserver) {
        try {
            if (request == null) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Request cannot be null")
                        .asRuntimeException());
                return;
            }

            String tenantIdStr = request.getTenantId();
            if (tenantIdStr == null || tenantIdStr.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("tenant_id must not be blank")
                        .asRuntimeException());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdStr.trim());
            } catch (IllegalArgumentException ex) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("Invalid tenant_id format: " + tenantIdStr)
                        .asRuntimeException());
                return;
            }

            if (request.getLimit() < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("limit must not be negative")
                        .asRuntimeException());
                return;
            }

            CriteriaBuilder<ProductEntity> queryBuilder = criteriaBuilderFactory.create(entityManager, ProductEntity.class, "p")
                    .fetch("p.category")
                    .where("p.tenant.id").eq(tenantId)
                    .where("p.archived").eq(false)
                    .where("p.available").eq(true);

            String query = request.getQuery();
            if (query != null && !query.trim().isEmpty()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                queryBuilder.whereOr()
                        .where("LOWER(p.name)").like().value(pattern).noEscape()
                        .where("LOWER(p.category.name)").like().value(pattern).noEscape()
                        .endOr();
            }

            if (request.getLimit() > 0) {
                queryBuilder.setMaxResults(request.getLimit());
            }

            queryBuilder.orderByAsc("p.displayOrder").orderByAsc("p.name");

            List<ProductEntity> products = queryBuilder.getResultList();

            GetCatalogItemResponse response = catalogMapper != null
                    ? catalogMapper.toResponse(tenantIdStr.trim(), products)
                    : GrpcCatalogMapper.toGetCatalogItemResponse(tenantIdStr.trim(), products);

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to retrieve catalog items for request: {}", request, ex);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error retrieving catalog items")
                    .asRuntimeException());
        }
    }
}
