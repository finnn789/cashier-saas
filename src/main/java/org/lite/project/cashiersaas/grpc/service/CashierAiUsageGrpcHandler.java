package org.lite.project.cashiersaas.grpc.service;

import com.cashier.grpc.v1.ConsumeAiQuestionRequest;
import com.cashier.grpc.v1.ConsumeAiQuestionResponse;
import com.cashier.grpc.v1.RecordAiUsageRequest;
import com.cashier.grpc.v1.RecordAiUsageResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * AI question quota and token usage. Not transactional itself: each AiUsageRepository call is its own transaction,
 * so a failed usage write can be caught here without poisoning an outer transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashierAiUsageGrpcHandler {

    private static final String MONTHLY_QUOTA_EXHAUSTED = "MONTHLY_QUOTA_EXHAUSTED";
    private static final String DAILY_CAP_REACHED = "DAILY_CAP_REACHED";

    private final GrpcTenantAccessPolicy tenantAccessPolicy;
    private final TenantRepository tenantRepository;
    private final AiUsageRepository usageRepository;

    public void consumeAiQuestion(ConsumeAiQuestionRequest request, StreamObserver<ConsumeAiQuestionResponse> responseObserver) {
        if (request == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Request cannot be null").asRuntimeException());
            return;
        }
        try {
            UUID tenantId = tenantAccessPolicy.requireAllowedTenant(request.getTenantId());
            tenantAccessPolicy.requireOwnerOutlets(tenantId, request.getUserId());
            ConsumeAiQuestionResponse.Builder response = ConsumeAiQuestionResponse.newBuilder();
            if (!tenantAccessPolicy.hasPremium(tenantId)) {
                response.setReason(GrpcTenantAccessPolicy.PLAN_PREMIUM_REQUIRED);
            } else {
                LocalDate today = LocalDate.now(tenantZone(tenantId));
                AiUsageRepository.Quota quota = usageRepository.consumeQuestion(tenantId, today.withDayOfMonth(1), today);
                int limit = AiUsageRepository.MONTHLY_QUESTIONS + quota.getBonus();
                response.setAllowed(quota.isAllowed()).setUsed(quota.getUsed()).setLimit(limit)
                        .setWarn(quota.getUsed() * 5L >= limit * 4L);
                if (!quota.isAllowed()) {
                    response.setReason(quota.isDailyCapReached() ? DAILY_CAP_REACHED : MONTHLY_QUOTA_EXHAUSTED);
                }
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("AI question quota failed for tenant={} cause={}",
                    GrpcTenantAccessPolicy.shortTenantRef(request.getTenantId()), e.getClass().getSimpleName());
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error consuming AI question").asRuntimeException());
        }
    }

    /** Best effort: the request carries no user, so only the tenant check applies; any failure is logged and swallowed. */
    public void recordAiUsage(RecordAiUsageRequest request, StreamObserver<RecordAiUsageResponse> responseObserver) {
        try {
            UUID tenantId = tenantAccessPolicy.requireAllowedTenant(request.getTenantId());
            LocalDate today = LocalDate.now(tenantZone(tenantId));
            usageRepository.addTokens(tenantId, today.withDayOfMonth(1), today,
                    Math.max(0L, request.getInputTokens()), Math.max(0L, request.getOutputTokens()));
        } catch (Exception e) {
            log.warn("AI usage was not recorded cause={}", e.getClass().getSimpleName());
        }
        responseObserver.onNext(RecordAiUsageResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private ZoneId tenantZone(UUID tenantId) {
        return ZoneId.of(tenantRepository.findById(tenantId).orElseThrow().getTimeZone());
    }
}
