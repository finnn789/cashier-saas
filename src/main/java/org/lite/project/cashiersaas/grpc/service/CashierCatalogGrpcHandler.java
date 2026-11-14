package org.lite.project.cashiersaas.grpc.service;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.grpc.mapper.GrpcCatalogMapper;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
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
    private final GrpcTenantAccessPolicy tenantAccessPolicy;

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

            UUID tenantId = tenantAccessPolicy.requireAllowedTenant(request.getTenantId());
            List<UUID> outletIds = tenantAccessPolicy.requireMemberOutlets(tenantId, request.getUserId());
            int limit = tenantAccessPolicy.clampLimit(request.getLimit());

            CriteriaBuilder<ProductEntity> queryBuilder = criteriaBuilderFactory.create(entityManager, ProductEntity.class, "p")
                    .fetch("p.category")
                    .where("p.tenant.id").eq(tenantId)
                    .where("p.outlet.id").in(outletIds)
                    .where("p.archived").eq(false)
                    .where("p.available").eq(true);

            String query = request.getQuery();
            if (query != null && !query.trim().isEmpty()) {
                String pattern = "%" + GrpcTenantAccessPolicy.escapeLikePattern(query.trim().toLowerCase()) + "%";
                queryBuilder.whereOr()
                        .where("LOWER(p.name)").like().value(pattern).escape('\\')
                        .where("LOWER(p.category.name)").like().value(pattern).escape('\\')
                        .endOr();
            }

            queryBuilder.setMaxResults(limit);
            queryBuilder.orderByAsc("p.displayOrder").orderByAsc("p.name");

            List<ProductEntity> products = queryBuilder.getResultList();

            String tenantRef = tenantId.toString();
            GetCatalogItemResponse response = catalogMapper != null
                    ? catalogMapper.toResponse(tenantRef, products)
                    : GrpcCatalogMapper.toGetCatalogItemResponse(tenantRef, products);

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException ex) {
            responseObserver.onError(ex);
        } catch (Exception ex) {
            log.error("Failed to retrieve catalog items for tenant={} cause={}",
                    request != null ? GrpcTenantAccessPolicy.shortTenantRef(request.getTenantId()) : "none",
                    ex.getClass().getSimpleName());
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error retrieving catalog items")
                    .asRuntimeException());
        }
    }
}
