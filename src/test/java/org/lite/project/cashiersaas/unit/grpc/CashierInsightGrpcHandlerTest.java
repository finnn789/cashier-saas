package org.lite.project.cashiersaas.unit.grpc;

import com.cashier.grpc.v1.GetAnomaliesRequest;
import com.cashier.grpc.v1.GetAnomaliesResponse;
import com.cashier.grpc.v1.GetRestockSuggestionsRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsResponse;
import com.cashier.grpc.v1.GetSalesSummaryRequest;
import com.cashier.grpc.v1.GetSalesSummaryResponse;
import com.cashier.grpc.v1.OutletAnomalies;
import com.cashier.grpc.v1.OutletRestock;
import com.cashier.grpc.v1.OutletSalesSummary;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.dto.response.FnbOwnerSalesInsight;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.grpc.mapper.GrpcInsightMapper;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
import org.lite.project.cashiersaas.grpc.service.CashierInsightGrpcHandler;
import org.lite.project.cashiersaas.repository.MerchantAccessRepository;
import org.lite.project.cashiersaas.service.FnbOwnerInsightService;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Validation, authorization order, delegation and error mapping of the owner insight RPCs (TASK-080). */
@Tag("unit")
class CashierInsightGrpcHandlerTest {
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "55555555-5555-5555-5555-555555555555";

    private final UUID tenantId = UUID.fromString(TENANT);
    private final UUID userId = UUID.fromString(USER);
    private final UUID outletId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private final OutletEntity outlet = OutletEntity.open(tenant, "Outlet", "Asia/Jakarta", null, null);
    private final TenantMembershipEntity ownerMembership =
            TenantMembershipEntity.grantOwner(StaffUserEntity.createCashier("o@example.com", "hash", "Owner", null), tenant);

    private GrpcTenantAccessPolicy policy;
    private MerchantAccessRepository accessRepository;
    private FnbOwnerInsightService insightService;
    private GrpcInsightMapper mapper;
    private CashierInsightGrpcHandler handler;
    private StreamObserver<GetSalesSummaryResponse> salesObserver;
    private StreamObserver<GetRestockSuggestionsResponse> restockObserver;
    private StreamObserver<GetAnomaliesResponse> anomalyObserver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        policy = mock(GrpcTenantAccessPolicy.class);
        accessRepository = mock(MerchantAccessRepository.class);
        insightService = mock(FnbOwnerInsightService.class);
        mapper = mock(GrpcInsightMapper.class);
        salesObserver = mock(StreamObserver.class);
        restockObserver = mock(StreamObserver.class);
        anomalyObserver = mock(StreamObserver.class);
        handler = new CashierInsightGrpcHandler(policy, accessRepository, insightService, mapper);
        when(policy.requireAllowedTenant(TENANT)).thenReturn(tenantId);
        when(policy.requireOwnerOutlets(tenantId, USER)).thenReturn(List.of(outletId));
        org.springframework.test.util.ReflectionTestUtils.setField(outlet, "id", outletId);
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(outletId), eq(Set.of(Role.OWNERMERCHANT)), any()))
                .thenReturn(Optional.of(new MerchantAccessRepository.OutletAccess(ownerMembership, outlet)));
    }

    private static GetSalesSummaryRequest sales(String start, String end) {
        return GetSalesSummaryRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setStartDate(start).setEndDate(end).build();
    }

    private StatusRuntimeException salesError(GetSalesSummaryRequest request, Status.Code expected) {
        handler.getSalesSummary(request, salesObserver);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(salesObserver).onError(error.capture());
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, error.getValue());
        assertEquals(expected, ex.getStatus().getCode());
        verify(salesObserver, never()).onNext(any());
        verify(salesObserver, never()).onCompleted();
        verifyNoInteractions(insightService, mapper);
        return ex;
    }

    @ParameterizedTest
    @CsvSource({
            "not-a-date, 2026-11-07",
            "2026-11-01, 07-11-2026",
            "2026-13-01, 2026-11-07",
            "2026-02-30, 2026-11-07",
            "'', 2026-11-07",
            "2026-11-01, ''",
            "2026-11-07, 2026-11-01",
            "2026-01-01, 2027-01-02",
            "2020-01-01, 2026-11-07"
    })
    void salesSummaryRejectsBadDatesReversedAndTooLongRangesAsInvalidArgument(String start, String end) {
        salesError(sales(start, end), Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(accessRepository);
    }

    @Test
    void salesSummaryAcceptsTheLongestAllowedRangeOf365DaysAndASingleDay() {
        when(insightService.salesInsight(any(MerchantAccessContext.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new FnbOwnerSalesInsight());
        when(mapper.toSalesSummary(any(MerchantAccessContext.class), any(FnbOwnerSalesInsight.class)))
                .thenReturn(OutletSalesSummary.getDefaultInstance());

        handler.getSalesSummary(sales("2026-01-01", "2027-01-01"), salesObserver); // 365 days apart
        handler.getSalesSummary(sales("2026-11-07", "2026-11-07"), salesObserver);

        verify(salesObserver, never()).onError(any());
        verify(salesObserver, org.mockito.Mockito.times(2)).onCompleted();
    }

    @Test
    void salesSummaryTrimsDatesBuildsOneContextPerOwnedOutletAndReturnsTheMappedRows() {
        FnbOwnerSalesInsight insight = new FnbOwnerSalesInsight();
        OutletSalesSummary row = OutletSalesSummary.newBuilder().setOutletId(outletId.toString()).build();
        when(insightService.salesInsight(any(MerchantAccessContext.class), eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 7))))
                .thenReturn(insight);
        when(mapper.toSalesSummary(any(MerchantAccessContext.class), eq(insight))).thenReturn(row);

        handler.getSalesSummary(sales(" 2026-11-01 ", "2026-11-07"), salesObserver);

        ArgumentCaptor<MerchantAccessContext> context = ArgumentCaptor.forClass(MerchantAccessContext.class);
        verify(insightService).salesInsight(context.capture(), any(LocalDate.class), any(LocalDate.class));
        assertEquals(outlet, context.getValue().getOutlet());
        assertEquals(ownerMembership, context.getValue().getMembership());
        ArgumentCaptor<GetSalesSummaryResponse> response = ArgumentCaptor.forClass(GetSalesSummaryResponse.class);
        verify(salesObserver).onNext(response.capture());
        verify(salesObserver).onCompleted();
        assertEquals(TENANT, response.getValue().getTenantId());
        assertEquals(List.of(row), response.getValue().getOutletsList());
    }

    @Test
    void anOutletWhoseAccessCheckFailsIsSkippedInsteadOfFailingTheCall() {
        UUID lostOutlet = UUID.randomUUID();
        when(policy.requireOwnerOutlets(tenantId, USER)).thenReturn(List.of(lostOutlet, outletId));
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(lostOutlet), any(), any())).thenReturn(Optional.empty());
        when(insightService.restock(any(MerchantAccessContext.class))).thenReturn(List.of());
        when(mapper.toRestock(any(MerchantAccessContext.class), any())).thenReturn(OutletRestock.getDefaultInstance());

        handler.getRestockSuggestions(GetRestockSuggestionsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).build(), restockObserver);

        ArgumentCaptor<GetRestockSuggestionsResponse> response = ArgumentCaptor.forClass(GetRestockSuggestionsResponse.class);
        verify(restockObserver).onNext(response.capture());
        assertEquals(1, response.getValue().getOutletsCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "nope", "2026-13-40", "14-11-2026"})
    void anomaliesRejectBlankOrMalformedDates(String date) {
        handler.getAnomalies(GetAnomaliesRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setDate(date).build(), anomalyObserver);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(anomalyObserver).onError(error.capture());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertInstanceOf(StatusRuntimeException.class, error.getValue()).getStatus().getCode());
        verifyNoInteractions(insightService, accessRepository);
    }

    @Test
    void anomaliesDelegateWithTheParsedDate() {
        LocalDate date = LocalDate.of(2026, 11, 14);
        when(insightService.anomalies(any(MerchantAccessContext.class), eq(date))).thenReturn(List.of());
        when(mapper.toAnomalies(any(MerchantAccessContext.class), eq(date), any())).thenReturn(OutletAnomalies.getDefaultInstance());

        handler.getAnomalies(GetAnomaliesRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setDate("2026-11-14").build(), anomalyObserver);

        ArgumentCaptor<GetAnomaliesResponse> response = ArgumentCaptor.forClass(GetAnomaliesResponse.class);
        verify(anomalyObserver).onNext(response.capture());
        assertEquals(1, response.getValue().getOutletsCount());
        verify(anomalyObserver).onCompleted();
    }

    @Test
    void aRejectedCallerGetsPermissionDeniedBeforeAnyArgumentFeedback() {
        StatusRuntimeException denied = Status.PERMISSION_DENIED.withDescription("Tenant is not permitted for this service").asRuntimeException();
        when(policy.requireOwnerOutlets(tenantId, USER)).thenThrow(denied);

        StatusRuntimeException ex = salesError(sales("garbage", "garbage"), Status.Code.PERMISSION_DENIED);

        assertEquals("Tenant is not permitted for this service", ex.getStatus().getDescription());
        verifyNoInteractions(accessRepository);
    }

    @Test
    void aRejectedTenantIsPassedThroughForEveryRpc() {
        when(policy.requireAllowedTenant(TENANT)).thenThrow(Status.PERMISSION_DENIED.asRuntimeException());

        handler.getRestockSuggestions(GetRestockSuggestionsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).build(), restockObserver);
        handler.getAnomalies(GetAnomaliesRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setDate("2026-11-14").build(), anomalyObserver);

        verify(restockObserver).onError(any(StatusRuntimeException.class));
        verify(anomalyObserver).onError(any(StatusRuntimeException.class));
        verifyNoInteractions(insightService, accessRepository);
    }

    @Test
    void nullRequestsAreInvalidArgument() {
        handler.getSalesSummary(null, salesObserver);
        handler.getRestockSuggestions(null, restockObserver);
        handler.getAnomalies(null, anomalyObserver);

        assertInvalidArgument(salesObserver);
        assertInvalidArgument(restockObserver);
        assertInvalidArgument(anomalyObserver);
    }

    private static <R> void assertInvalidArgument(StreamObserver<R> observer) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertEquals(Status.Code.INVALID_ARGUMENT, ((StatusRuntimeException) error.getValue()).getStatus().getCode());
    }

    @Test
    void anUnexpectedFailureBecomesInternalWithAFixedMessageThatLeaksNothing() {
        when(insightService.restock(any(MerchantAccessContext.class))).thenThrow(new IllegalStateException("jdbc://secret-host failure"));

        handler.getRestockSuggestions(GetRestockSuggestionsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).build(), restockObserver);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(restockObserver).onError(error.capture());
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, error.getValue());
        assertEquals(Status.Code.INTERNAL, ex.getStatus().getCode());
        assertEquals("Internal error retrieving restock suggestions", ex.getStatus().getDescription());
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains("secret"));
        verify(restockObserver, never()).onNext(any());
    }
}
