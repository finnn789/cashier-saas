package org.lite.project.cashiersaas.grpc.service;

import com.cashier.grpc.v1.GetAnomaliesRequest;
import com.cashier.grpc.v1.GetAnomaliesResponse;
import com.cashier.grpc.v1.GetRestockSuggestionsRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsResponse;
import com.cashier.grpc.v1.GetSalesSummaryRequest;
import com.cashier.grpc.v1.GetSalesSummaryResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.grpc.mapper.GrpcInsightMapper;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.lite.project.cashiersaas.service.FnbOwnerInsightService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Read-only owner insight RPCs (AC-09): owner role only, limited to the caller's active outlets. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashierInsightGrpcHandler {

    private static final int MAX_RANGE_DAYS = 366;
    private static final Set<Role> OWNER_ROLES = Set.of(Role.OWNERMERCHANT);

    private final GrpcTenantAccessPolicy tenantAccessPolicy;
    private final MerchantAccessRepository accessRepository;
    private final FnbOwnerInsightService insightService;
    private final GrpcInsightMapper mapper;

    @Transactional(readOnly = true)
    public void getSalesSummary(GetSalesSummaryRequest request, StreamObserver<GetSalesSummaryResponse> responseObserver) {
        if (request == null) {
            nullRequest(responseObserver);
            return;
        }
        handle(request.getTenantId(), request.getUserId(), "Internal error retrieving sales summary", responseObserver,
                tenantUuid -> {
                    LocalDate start = parseDate(request.getStartDate(), "start_date");
                    LocalDate end = parseDate(request.getEndDate(), "end_date");
                    if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
                        throw Status.INVALID_ARGUMENT.withDescription("date range is invalid").asRuntimeException();
                    }
                    return contexts -> {
                        GetSalesSummaryResponse.Builder response = GetSalesSummaryResponse.newBuilder().setTenantId(tenantUuid.toString());
                        for (MerchantAccessContext context : contexts) {
                            response.addOutlets(mapper.toSalesSummary(context, insightService.salesInsight(context, start, end)));
                        }
                        return response.build();
                    };
                });
    }

    @Transactional(readOnly = true)
    public void getRestockSuggestions(GetRestockSuggestionsRequest request,
                                      StreamObserver<GetRestockSuggestionsResponse> responseObserver) {
        if (request == null) {
            nullRequest(responseObserver);
            return;
        }
        handle(request.getTenantId(), request.getUserId(), "Internal error retrieving restock suggestions", responseObserver,
                tenantUuid -> contexts -> {
                    GetRestockSuggestionsResponse.Builder response = GetRestockSuggestionsResponse.newBuilder().setTenantId(tenantUuid.toString());
                    for (MerchantAccessContext context : contexts) {
                        response.addOutlets(mapper.toRestock(context, insightService.restock(context)));
                    }
                    return response.build();
                });
    }

    @Transactional(readOnly = true)
    public void getAnomalies(GetAnomaliesRequest request, StreamObserver<GetAnomaliesResponse> responseObserver) {
        if (request == null) {
            nullRequest(responseObserver);
            return;
        }
        handle(request.getTenantId(), request.getUserId(), "Internal error retrieving anomalies", responseObserver,
                tenantUuid -> {
                    LocalDate date = parseDate(request.getDate(), "date");
                    return contexts -> {
                        GetAnomaliesResponse.Builder response = GetAnomaliesResponse.newBuilder().setTenantId(tenantUuid.toString());
                        for (MerchantAccessContext context : contexts) {
                            response.addOutlets(mapper.toAnomalies(context, date, insightService.anomalies(context, date)));
                        }
                        return response.build();
                    };
                });
    }

    /**
     * Shared flow: tenant + owner check first (so a rejected caller learns nothing about argument validity),
     * then the RPC-specific validation, which returns the function that builds the response from outlet contexts.
     */
    private <R> void handle(String rawTenantId, String rawUserId, String internalMessage, StreamObserver<R> responseObserver,
                            Function<UUID, Function<List<MerchantAccessContext>, R>> validate) {
        try {
            UUID tenantUuid = tenantAccessPolicy.requireAllowedTenant(rawTenantId);
            List<UUID> outletIds = tenantAccessPolicy.requireOwnerOutlets(tenantUuid, rawUserId);
            tenantAccessPolicy.requirePremium(tenantUuid);
            Function<List<MerchantAccessContext>, R> builder = validate.apply(tenantUuid);
            UUID userId = UUID.fromString(rawUserId.trim());
            List<MerchantAccessContext> contexts = new ArrayList<>();
            Instant now = Instant.now();
            for (UUID outletId : outletIds) {
                accessRepository.findOutlet(userId, tenantUuid, outletId, OWNER_ROLES, now)
                        .ifPresent(access -> contexts.add(new MerchantAccessContext(
                                access.getMembership().getTenant(), access.getOutlet(), access.getMembership())));
            }
            responseObserver.onNext(builder.apply(contexts));
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("Owner insight RPC failed for tenant={} cause={}",
                    GrpcTenantAccessPolicy.shortTenantRef(rawTenantId), e.getClass().getSimpleName());
            responseObserver.onError(Status.INTERNAL.withDescription(internalMessage).asRuntimeException());
        }
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " must not be blank").asRuntimeException();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " must be an ISO date (yyyy-MM-dd)").asRuntimeException();
        }
    }

    private static <R> void nullRequest(StreamObserver<R> responseObserver) {
        responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Request cannot be null").asRuntimeException());
    }
}
