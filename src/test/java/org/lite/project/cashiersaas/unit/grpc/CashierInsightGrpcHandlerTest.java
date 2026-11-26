package org.lite.project.cashiersaas.unit.grpc;

import com.cashier.grpc.v1.GetAnomaliesRequest;
import com.cashier.grpc.v1.GetAnomaliesResponse;
import com.cashier.grpc.v1.GetLeakageReportRequest;
import com.cashier.grpc.v1.GetLeakageReportResponse;
import com.cashier.grpc.v1.GetMenuMarginsRequest;
import com.cashier.grpc.v1.GetMenuMarginsResponse;
import com.cashier.grpc.v1.GetRestockSuggestionsRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsResponse;
import com.cashier.grpc.v1.GetSalesSummaryRequest;
import com.cashier.grpc.v1.GetSalesSummaryResponse;
import com.cashier.grpc.v1.OutletAnomalies;
import com.cashier.grpc.v1.OutletLeakage;
import com.cashier.grpc.v1.OutletMenuMargins;
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
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginResponse;
import org.lite.project.cashiersaas.dto.response.FnbOutletLeakageResponse;
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
import org.lite.project.cashiersaas.service.FnbLeakageService;
import org.lite.project.cashiersaas.service.FnbMenuMarginService;
import org.lite.project.cashiersaas.service.FnbOwnerInsightService;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
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
    private FnbLeakageService leakageService;
    private FnbMenuMarginService menuMarginService;
    private GrpcInsightMapper mapper;
    private CashierInsightGrpcHandler handler;
    private StreamObserver<GetSalesSummaryResponse> salesObserver;
    private StreamObserver<GetRestockSuggestionsResponse> restockObserver;
    private StreamObserver<GetAnomaliesResponse> anomalyObserver;
    private StreamObserver<GetLeakageReportResponse> leakageObserver;
    private StreamObserver<GetMenuMarginsResponse> marginObserver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        policy = mock(GrpcTenantAccessPolicy.class);
        accessRepository = mock(MerchantAccessRepository.class);
        insightService = mock(FnbOwnerInsightService.class);
        leakageService = mock(FnbLeakageService.class);
        menuMarginService = mock(FnbMenuMarginService.class);
        mapper = mock(GrpcInsightMapper.class);
        salesObserver = mock(StreamObserver.class);
        restockObserver = mock(StreamObserver.class);
        anomalyObserver = mock(StreamObserver.class);
        leakageObserver = mock(StreamObserver.class);
        marginObserver = mock(StreamObserver.class);
        handler = new CashierInsightGrpcHandler(policy, accessRepository, insightService, leakageService,
                menuMarginService, mapper);
        when(policy.requireAllowedTenant(TENANT)).thenReturn(tenantId);
        when(policy.requireOwnerOutlets(tenantId, USER)).thenReturn(List.of(outletId));
        org.springframework.test.util.ReflectionTestUtils.setField(outlet, "id", outletId);
        when(accessRepository.findOutlet(eq(userId), eq(tenantId), eq(outletId), eq(Set.of(Role.OWNERMERCHANT)), any()))
                .thenReturn(Optional.of(new MerchantAccessRepository.OutletAccess(ownerMembership, outlet, true)));
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

    @ParameterizedTest
    @CsvSource({
            "garbage, ''",
            "'', 2026-13-01",
            "2026-11-07, 2026-11-01",
            "2026-01-01, 2026-04-03"
    })
    void leakageRejectsBadDatesReversedAndRangesOf92DaysOrMoreAsInvalidArgument(String from, String to) {
        handler.getLeakageReport(GetLeakageReportRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setFrom(from).setTo(to).build(),
                leakageObserver);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(leakageObserver).onError(error.capture());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertInstanceOf(StatusRuntimeException.class, error.getValue()).getStatus().getCode());
        verify(leakageObserver, never()).onNext(any());
        verifyNoInteractions(leakageService, mapper);
    }

    @ParameterizedTest
    @CsvSource({
            // longest allowed range (91 days apart), read in the tenant zone Asia/Jakarta (UTC+7)
            "2026-01-01, 2026-04-02, 2025-12-31T17:00:00Z, 2026-04-02T17:00:00Z",
            // no from: 30 days ending at `to`
            "'', 2026-11-30, 2026-10-31T17:00:00Z, 2026-11-30T17:00:00Z"
    })
    void leakageReadsTheCalendarRangeInTheTenantZoneAndReturnsTheMappedOutlets(String from, String to, Instant start, Instant end) {
        FnbOutletLeakageResponse outletLeakage = new FnbOutletLeakageResponse();
        OutletLeakage row = OutletLeakage.newBuilder().setOutletId(outletId.toString()).build();
        when(leakageService.outletsLeakage(tenantId, List.of(outletId), start, end, 10)).thenReturn(List.of(outletLeakage));
        when(mapper.toLeakage(outletLeakage)).thenReturn(row);

        handler.getLeakageReport(GetLeakageReportRequest.newBuilder().setTenantId(TENANT).setUserId(USER)
                .setFrom(from).setTo(to).build(), leakageObserver);

        ArgumentCaptor<GetLeakageReportResponse> response = ArgumentCaptor.forClass(GetLeakageReportResponse.class);
        verify(leakageObserver).onNext(response.capture());
        verify(leakageObserver).onCompleted();
        assertEquals(TENANT, response.getValue().getTenantId());
        assertEquals(List.of(row), response.getValue().getOutletsList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "100.01", "NaN"})
    void menuMarginsRejectAnInvalidMaxMarginAsInvalidArgument(String max) {
        handler.getMenuMargins(GetMenuMarginsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setMaxMarginPct(max).build(), marginObserver);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(marginObserver).onError(error.capture());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertInstanceOf(StatusRuntimeException.class, error.getValue()).getStatus().getCode());
        verifyNoInteractions(menuMarginService, mapper);
    }

    @ParameterizedTest
    @CsvSource({
            "'', 'Kopi;Teh'",           // no max: only BELOW_TARGET menus
            "50, 'Kopi;Teh'",           // max is inclusive; unknown margins never match
            "70, 'Kopi;Roti;Teh'",
            "49.99, 'Kopi'"
    })
    void menuMarginsFilterByTheMaxMarginKeepingTheServiceOrder(String max, String expectedNames) {
        FnbMenuMarginReportResponse report = new FnbMenuMarginReportResponse();
        report.setMenus(List.of(menu("Kopi", "BELOW_TARGET", "30.00"), menu("Roti", "OK", "70.00"),
                menu("Teh", "BELOW_TARGET", "50.00"), menu("Susu", "COST_UNKNOWN", null), menu("Air", "NO_RECIPE", null)));
        when(menuMarginService.outletMargins(tenantId, outletId)).thenReturn(report);
        when(mapper.toMenuMargins(eq(report), any())).thenReturn(OutletMenuMargins.getDefaultInstance());

        handler.getMenuMargins(GetMenuMarginsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setMaxMarginPct(max).build(), marginObserver);

        ArgumentCaptor<List<FnbMenuMarginResponse>> menus = ArgumentCaptor.forClass(List.class);
        verify(mapper).toMenuMargins(eq(report), menus.capture());
        assertEquals(List.of(expectedNames.split(";")), menus.getValue().stream().map(FnbMenuMarginResponse::getName).toList());
        ArgumentCaptor<GetMenuMarginsResponse> response = ArgumentCaptor.forClass(GetMenuMarginsResponse.class);
        verify(marginObserver).onNext(response.capture());
        assertEquals(1, response.getValue().getOutletsCount());
        verify(marginObserver).onCompleted();
    }

    @Test
    void menuMarginsAreCappedAt30MenusPerOutletKeepingTheFirstOnes() {
        FnbMenuMarginReportResponse report = new FnbMenuMarginReportResponse();
        report.setMenus(java.util.stream.IntStream.range(0, 35).mapToObj(i -> menu("M" + i, "BELOW_TARGET", "10.00")).toList());
        when(menuMarginService.outletMargins(tenantId, outletId)).thenReturn(report);
        when(mapper.toMenuMargins(eq(report), any())).thenReturn(OutletMenuMargins.getDefaultInstance());

        handler.getMenuMargins(GetMenuMarginsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).build(), marginObserver);

        ArgumentCaptor<List<FnbMenuMarginResponse>> menus = ArgumentCaptor.forClass(List.class);
        verify(mapper).toMenuMargins(eq(report), menus.capture());
        assertEquals(30, menus.getValue().size());
        assertEquals("M0", menus.getValue().get(0).getName());
        assertEquals("M29", menus.getValue().get(29).getName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void salesLeakageAndMarginRpcsAreDeniedBeforeArgumentFeedbackForANonOwnerOrNonPremiumTenant(boolean notPremium) {
        StatusRuntimeException denied = Status.PERMISSION_DENIED.withDescription("Tenant is not permitted for this service").asRuntimeException();
        if (notPremium) org.mockito.Mockito.doThrow(denied).when(policy).requirePremium(tenantId);
        else when(policy.requireOwnerOutlets(tenantId, USER)).thenThrow(denied);

        StatusRuntimeException salesDenied = salesError(sales("garbage", "garbage"), Status.Code.PERMISSION_DENIED);
        handler.getLeakageReport(GetLeakageReportRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setFrom("garbage").build(), leakageObserver);
        handler.getMenuMargins(GetMenuMarginsRequest.newBuilder().setTenantId(TENANT).setUserId(USER).setMaxMarginPct("garbage").build(), marginObserver);

        ArgumentCaptor<Throwable> leakageError = ArgumentCaptor.forClass(Throwable.class);
        ArgumentCaptor<Throwable> marginError = ArgumentCaptor.forClass(Throwable.class);
        verify(leakageObserver).onError(leakageError.capture());
        verify(marginObserver).onError(marginError.capture());
        // permission is checked before any argument feedback
        assertEquals("Tenant is not permitted for this service", salesDenied.getStatus().getDescription());
        assertEquals(Status.Code.PERMISSION_DENIED, ((StatusRuntimeException) leakageError.getValue()).getStatus().getCode());
        assertEquals(Status.Code.PERMISSION_DENIED, ((StatusRuntimeException) marginError.getValue()).getStatus().getCode());
        verifyNoInteractions(leakageService, menuMarginService, accessRepository);
    }

    private static FnbMenuMarginResponse menu(String name, String status, String marginPct) {
        FnbMenuMarginResponse menu = new FnbMenuMarginResponse();
        menu.setName(name);
        menu.setStatus(status);
        menu.setMarginPct(marginPct == null ? null : new BigDecimal(marginPct));
        return menu;
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
