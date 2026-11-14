package org.lite.project.cashiersaas.unit.grpc;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import com.cashier.grpc.v1.GetOrderStatusRequest;
import com.cashier.grpc.v1.GetOrderStatusResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.grpc.mapper.GrpcCatalogMapper;
import org.lite.project.cashiersaas.grpc.mapper.GrpcOrderMapper;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
import org.lite.project.cashiersaas.grpc.service.CashierCatalogGrpcHandler;
import org.lite.project.cashiersaas.grpc.service.CashierOrderGrpcHandler;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.TenantMembershipRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class CashierGrpcHandlerRejectionTest {

    private static final String ALLOWED = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final String USER = "55555555-5555-5555-5555-555555555555";
    private static final String OUTSIDER = "66666666-6666-6666-6666-666666666666";

    private TenantRepository tenantRepository;
    private MembershipOutletAccessRepository outletAccessRepository;
    private EntityManager entityManager;
    private CriteriaBuilderFactory criteriaBuilderFactory;
    private GrpcCatalogMapper catalogMapper;
    private GrpcOrderMapper orderMapper;
    private CashierCatalogGrpcHandler catalogHandler;
    private CashierOrderGrpcHandler orderHandler;
    private StreamObserver<GetCatalogItemResponse> catalogObserver;
    private StreamObserver<GetOrderStatusResponse> orderObserver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        criteriaBuilderFactory = mock(CriteriaBuilderFactory.class);
        catalogMapper = mock(GrpcCatalogMapper.class);
        orderMapper = mock(GrpcOrderMapper.class);
        catalogObserver = mock(StreamObserver.class);
        orderObserver = mock(StreamObserver.class);

        GrpcSecurityProperties properties = new GrpcSecurityProperties();
        properties.setServiceKey("unit-test-internal-service-key-0001");
        tenantRepository = mock(TenantRepository.class);
        outletAccessRepository = mock(MembershipOutletAccessRepository.class);
        when(outletAccessRepository.findActiveOutletIds(UUID.fromString(ALLOWED), UUID.fromString(USER)))
                .thenReturn(List.of(UUID.randomUUID()));
        when(outletAccessRepository.findActiveOutletIds(UUID.fromString(ALLOWED), UUID.fromString(OUTSIDER)))
                .thenReturn(List.of());
        when(tenantRepository.existsActiveFnbTenant(UUID.fromString(ALLOWED))).thenReturn(true);
        when(tenantRepository.existsActiveFnbTenant(UUID.fromString(OTHER))).thenReturn(false);
        GrpcTenantAccessPolicy policy = new GrpcTenantAccessPolicy(tenantRepository, outletAccessRepository,
                mock(TenantMembershipRepository.class), properties);

        catalogHandler = new CashierCatalogGrpcHandler(entityManager, criteriaBuilderFactory, catalogMapper, policy);
        orderHandler = new CashierOrderGrpcHandler(entityManager, criteriaBuilderFactory, orderMapper, policy);
    }

    private StatusRuntimeException catalogRejected(Status.Code expected) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(catalogObserver).onError(error.capture());
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, error.getValue());
        assertEquals(expected, ex.getStatus().getCode());
        verify(catalogObserver, never()).onNext(any());
        verify(catalogObserver, never()).onCompleted();
        verifyNoInteractions(criteriaBuilderFactory, entityManager, catalogMapper);
        return ex;
    }

    private StatusRuntimeException orderRejected(Status.Code expected) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(orderObserver).onError(error.capture());
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, error.getValue());
        assertEquals(expected, ex.getStatus().getCode());
        verify(orderObserver, never()).onNext(any());
        verify(orderObserver, never()).onCompleted();
        verifyNoInteractions(criteriaBuilderFactory, entityManager, orderMapper);
        return ex;
    }

    // An empty tenant and query is the proto default instance, i.e. the missing-field case.
    @ParameterizedTest
    @CsvSource(value = {"'',''", "'   ',''", "not-a-uuid,latte"})
    void catalogRejectsBlankMissingOrInvalidTenant(String tenantId, String query) {
        catalogHandler.getCatalogItem(
                GetCatalogItemRequest.newBuilder().setTenantId(tenantId).setQuery(query).build(), catalogObserver);

        catalogRejected(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void catalogRejectsTenantNotActiveInTheDatabaseWithoutLeakingIds() {
        catalogHandler.getCatalogItem(
                GetCatalogItemRequest.newBuilder().setTenantId(OTHER).setLimit(1000).build(), catalogObserver);

        StatusRuntimeException ex = catalogRejected(Status.Code.PERMISSION_DENIED);
        String description = String.valueOf(ex.getStatus().getDescription());
        assertFalse(description.contains(OTHER));
        assertFalse(description.contains(ALLOWED));
    }

    @Test
    void catalogRejectsNullRequest() {
        catalogHandler.getCatalogItem(null, catalogObserver);

        catalogRejected(Status.Code.INVALID_ARGUMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void orderRejectsBlankTenant(String tenantId) {
        orderHandler.getOrderStatus(
                GetOrderStatusRequest.newBuilder().setTenantId(tenantId).setOrderNumber("ORD-1").build(),
                orderObserver);

        orderRejected(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void orderRejectsInvalidTenantUuid() {
        orderHandler.getOrderStatus(
                GetOrderStatusRequest.newBuilder().setTenantId("1234").setOrderNumber("ORD-1").build(),
                orderObserver);

        orderRejected(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void orderRejectsTenantNotActiveInTheDatabaseBeforeLookingUpTheOrder() {
        orderHandler.getOrderStatus(
                GetOrderStatusRequest.newBuilder().setTenantId(OTHER).setOrderNumber("ORD-1").build(),
                orderObserver);

        StatusRuntimeException ex = orderRejected(Status.Code.PERMISSION_DENIED);
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains(OTHER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void orderRejectsBlankOrderNumberForAllowedTenant(String orderNumber) {
        orderHandler.getOrderStatus(
                GetOrderStatusRequest.newBuilder().setTenantId(ALLOWED).setUserId(USER).setOrderNumber(orderNumber).build(),
                orderObserver);

        orderRejected(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void orderRejectsNullRequest() {
        orderHandler.getOrderStatus(null, orderObserver);

        orderRejected(Status.Code.INVALID_ARGUMENT);
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "nope"})
    void catalogRejectsMissingOrInvalidUserForAllowedTenant(String userId) {
        catalogHandler.getCatalogItem(
                GetCatalogItemRequest.newBuilder().setTenantId(ALLOWED).setUserId(userId).build(), catalogObserver);

        catalogRejected(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void catalogRejectsUserWithoutOutletAccess() {
        catalogHandler.getCatalogItem(
                GetCatalogItemRequest.newBuilder().setTenantId(ALLOWED).setUserId(OUTSIDER).build(), catalogObserver);

        StatusRuntimeException ex = catalogRejected(Status.Code.PERMISSION_DENIED);
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains(OUTSIDER));
    }

    @Test
    void orderRejectsMissingUserForAllowedTenant() {
        orderHandler.getOrderStatus(
                GetOrderStatusRequest.newBuilder().setTenantId(ALLOWED).setOrderNumber("ORD-1").build(), orderObserver);

        orderRejected(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void orderRejectsUserWithoutOutletAccessBeforeLookingUpTheOrder() {
        orderHandler.getOrderStatus(
                GetOrderStatusRequest.newBuilder().setTenantId(ALLOWED).setUserId(OUTSIDER).setOrderNumber("ORD-1")
                        .build(), orderObserver);

        StatusRuntimeException ex = orderRejected(Status.Code.PERMISSION_DENIED);
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains(OUTSIDER));
    }
}
