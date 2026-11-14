package org.lite.project.cashiersaas.integration.grpc;

import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.CatalogItem;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import com.cashier.grpc.v1.GetOrderStatusRequest;
import com.cashier.grpc.v1.GetOrderStatusResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC server security against the real Spring context, real PostgreSQL and a real network channel:
 * service-key authentication, database-backed tenant authorization (tenant must exist, be ACTIVE and have F&B
 * enabled), input limits, LIKE escaping, tenant isolation of the order lookup and disabled server reflection.
 *
 * <p>Tenants A1 and A2 are active F&B tenants and therefore allowed without any configuration. The disabled,
 * pending-profile, non-F&B and no-business-type tenants exist in the database but must be denied exactly like a
 * tenant that does not exist at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrpcSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Metadata.Key<String> SERVICE_KEY_HEADER =
            Metadata.Key.of("x-internal-service-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final UUID TENANT_A1 = UUID.fromString("a1a1a1a1-0000-4000-8000-0000000000a1");
    private static final UUID TENANT_A2 = UUID.fromString("a2a2a2a2-0000-4000-8000-0000000000a2");
    /** Active F&B tenant whose status is flipped by a test to prove there is no stale allow decision. */
    private static final UUID TENANT_TOGGLE = UUID.fromString("b4b4b4b4-0000-4000-8000-0000000000b4");
    private static final UUID TENANT_DISABLED = UUID.fromString("c3c3c3c3-0000-4000-8000-0000000000c3");
    private static final UUID TENANT_PENDING = UUID.fromString("e5e5e5e5-0000-4000-8000-0000000000e5");
    private static final UUID TENANT_NON_FNB = UUID.fromString("f6f6f6f6-0000-4000-8000-0000000000f6");
    private static final UUID TENANT_NO_BUSINESS_TYPE = UUID.fromString("a7a7a7a7-0000-4000-8000-0000000000a7");
    private static final UUID TENANT_UNKNOWN = UUID.fromString("dddddddd-0000-4000-8000-0000000000dd");
    private static final String DENIED_MESSAGE = "Tenant is not permitted for this service";

    // Staff users the gRPC caller acts for (Ai-services sends the verified Java user id with every call).
    private static final UUID USER_A1 = UUID.fromString("00000001-0000-4000-8000-0000000000a1");
    private static final UUID USER_A1_OUTLET2 = UUID.fromString("00000002-0000-4000-8000-0000000000a1");
    private static final UUID USER_A1_DEACTIVATED = UUID.fromString("00000003-0000-4000-8000-0000000000a1");
    private static final UUID USER_A1_DISABLED = UUID.fromString("00000004-0000-4000-8000-0000000000a1");
    private static final UUID USER_A2 = UUID.fromString("00000001-0000-4000-8000-0000000000a2");
    private static final UUID USER_TOGGLE = UUID.fromString("00000001-0000-4000-8000-0000000000b4");
    /** Active staff user without any membership. */
    private static final UUID USER_OUTSIDER = UUID.fromString("00000009-0000-4000-8000-000000000009");

    private static final String ORDER_A1_PAID = "ORD-A1-0001";
    private static final String ORDER_A1_OPEN = "ORD-A1-0002";
    private static final String ORDER_A1_OUTLET2 = "ORD-A1-0003";
    private static final String ORDER_A2 = "ORD-A2-0001";
    private static final String ORDER_C = "ORD-C-0001";
    private static final int BULK_PRODUCTS = 60;

    @TestConfiguration
    static class PortConfiguration {
        @Bean
        GrpcServerPortHolder grpcServerPortHolder() {
            return new GrpcServerPortHolder();
        }
    }

    @Autowired
    private DataSource dataSource;
    @Autowired
    private GrpcServerPortHolder portHolder;

    private JdbcTemplate jdbc;
    private ManagedChannel channel;
    private UUID a1KopiSusuId;
    private UUID a1Outlet;
    private UUID a1Outlet2;
    private UUID a2Outlet;
    private UUID toggleOutlet;

    @BeforeAll
    void setUpAll() {
        jdbc = new JdbcTemplate(dataSource);
        ensureBusinessType("FNB", "Food and Beverage");
        ensureBusinessType("OTHER", "Other business");
        seedTenantA1();
        seedTenantA2();
        seedTenantDisabled();
        seedTenant(TENANT_TOGGLE, "Warung Toggle", "ACTIVE", "FNB");
        toggleOutlet = seedOutlet(TENANT_TOGGLE, "Outlet Toggle");
        seedTenant(TENANT_PENDING, "Warung Pending", "PENDING_PROFILE", "FNB");
        seedTenant(TENANT_NON_FNB, "Toko Lain", "ACTIVE", "OTHER");
        seedTenant(TENANT_NO_BUSINESS_TYPE, "Tanpa Tipe", "ACTIVE", null);
        seedStaffAccess();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", portHolder.port()).usePlaintext().build();
    }

    @AfterAll
    void cleanUp() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        List<String> tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename NOT IN ('flyway_schema_history', 'business_types')", String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
        // Reference data shared with other test classes: keep FNB, remove only what this class added.
        jdbc.update("DELETE FROM business_types WHERE code = 'OTHER'");
    }

    // ---------------------------------------------------------------- service key authentication

    @Test
    void missingServiceKeyIsUnauthenticatedForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = stub(null);
        GetCatalogItemRequest catalog = catalogRequest(TENANT_A1, "", 0);
        GetOrderStatusRequest order = orderRequest(TENANT_A1, ORDER_A1_PAID);

        assertCode(Status.Code.UNAUTHENTICATED, () -> stub.getCatalogItem(catalog));
        assertCode(Status.Code.UNAUTHENTICATED, () -> stub.getOrderStatus(order));
    }

    @Test
    void wrongServiceKeyIsUnauthenticatedAndNeverEchoed() {
        List<String> badKeys = new ArrayList<>();
        badKeys.add("wrong-key-wrong-key-wrong-key-1234");
        badKeys.add("");
        badKeys.add(GRPC_TEST_SERVICE_KEY + "x");
        badKeys.add(GRPC_TEST_SERVICE_KEY.toUpperCase());

        for (String badKey : badKeys) {
            CashierServiceGrpc.CashierServiceBlockingStub stub = stub(badKey);
            StatusRuntimeException catalogError = assertCode(Status.Code.UNAUTHENTICATED,
                    () -> stub.getCatalogItem(catalogRequest(TENANT_A1, "", 0)));
            StatusRuntimeException orderError = assertCode(Status.Code.UNAUTHENTICATED,
                    () -> stub.getOrderStatus(orderRequest(TENANT_A1, ORDER_A1_PAID)));
            assertDoesNotLeak(catalogError, GRPC_TEST_SERVICE_KEY, TENANT_A1.toString());
            assertDoesNotLeak(orderError, GRPC_TEST_SERVICE_KEY, TENANT_A1.toString());
        }
    }

    @Test
    void keyThatIsAPrefixOfTheRealKeyIsUnauthenticated() {
        String prefix = GRPC_TEST_SERVICE_KEY.substring(0, GRPC_TEST_SERVICE_KEY.length() - 5);
        CashierServiceGrpc.CashierServiceBlockingStub stub = stub(prefix);

        assertCode(Status.Code.UNAUTHENTICATED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_A1, "", 0)));
        assertCode(Status.Code.UNAUTHENTICATED,
                () -> stub.getOrderStatus(orderRequest(TENANT_A1, ORDER_A1_PAID)));
    }

    // ---------------------------------------------------------------- database tenant authorization

    @Test
    void activeFnbTenantSeesOnlyItsOwnAvailableNonArchivedProducts() {
        GetCatalogItemResponse response = validStub().getCatalogItem(catalogRequest(TENANT_A1, "kopi", 0));

        assertEquals(TENANT_A1.toString(), response.getTenantId());
        assertEquals(List.of("Kopi Susu"), names(response),
                "archived, unavailable and other tenants' 'Kopi' products must not appear");
        CatalogItem item = response.getItems(0);
        assertEquals(a1KopiSusuId.toString(), item.getId());
        assertEquals("18000.00", item.getPriceAmount());
        assertTrue(item.getIsAvailable());
        assertEquals("Minuman", item.getCategory());
    }

    @Test
    void queryIsCaseInsensitiveAndMatchesCategoryName() {
        assertEquals(List.of("Kopi Susu"),
                names(validStub().getCatalogItem(catalogRequest(TENANT_A1, "  KOPI SUSU  ", 0))));
        assertEquals(List.of("Kopi Susu"),
                names(validStub().getCatalogItem(catalogRequest(TENANT_A1, "minuman", 0))));
    }

    @Test
    void otherActiveTenantNeverSeesTenantA1Products() {
        GetCatalogItemResponse a2 = validStub().getCatalogItem(catalogRequest(TENANT_A2, "kopi", 0));

        assertEquals(TENANT_A2.toString(), a2.getTenantId());
        assertEquals(List.of("Kopi Tetangga"), names(a2));
        assertTrue(names(validStub().getCatalogItem(catalogRequest(TENANT_A2, "bulk", 0))).isEmpty(),
                "A1 bulk products must not leak to A2");
    }

    @Test
    void disabledTenantWithDataIsPermissionDeniedForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException catalogError = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_DISABLED, "", 0)));
        StatusRuntimeException orderError = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getOrderStatus(orderRequest(TENANT_DISABLED, ORDER_C)));
        assertDoesNotLeak(catalogError, TENANT_DISABLED.toString(), ORDER_C);
        assertDoesNotLeak(orderError, TENANT_DISABLED.toString(), ORDER_C);
    }

    @Test
    void unknownTenantIsPermissionDeniedForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException catalogError = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_UNKNOWN, "", 0)));
        StatusRuntimeException orderError = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getOrderStatus(orderRequest(TENANT_UNKNOWN, ORDER_C)));
        assertDoesNotLeak(catalogError, TENANT_UNKNOWN.toString(), ORDER_C);
        assertDoesNotLeak(orderError, TENANT_UNKNOWN.toString(), ORDER_C);
    }

    @Test
    void unknownTenantIsIndistinguishableFromEveryKindOfDeniedExistingTenant() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException unknown = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_UNKNOWN, "", 0)));
        assertEquals(DENIED_MESSAGE, unknown.getStatus().getDescription());

        for (UUID deniedExisting : new UUID[] {TENANT_DISABLED, TENANT_PENDING, TENANT_NON_FNB,
                TENANT_NO_BUSINESS_TYPE}) {
            StatusRuntimeException catalogDenied = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub.getCatalogItem(catalogRequest(deniedExisting, "", 0)));
            StatusRuntimeException orderDenied = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub.getOrderStatus(orderRequest(deniedExisting, ORDER_C)));
            StatusRuntimeException unknownOrder = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub.getOrderStatus(orderRequest(TENANT_UNKNOWN, ORDER_C)));

            assertEquals(unknown.getStatus().getCode(), catalogDenied.getStatus().getCode());
            assertEquals(unknown.getStatus().getDescription(), catalogDenied.getStatus().getDescription(),
                    "existing denied tenant " + deniedExisting + " must look like an unknown tenant");
            assertEquals(unknownOrder.getStatus().getDescription(), orderDenied.getStatus().getDescription());
        }
    }

    @Test
    void tenantIsAllowedOnlyWhileItIsActiveWithoutStaleDecisions() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();
        GetCatalogItemRequest request = catalogRequest(TENANT_TOGGLE, "", 0);

        assertEquals(TENANT_TOGGLE.toString(), stub.getCatalogItem(request).getTenantId());

        jdbc.update("UPDATE tenants SET status = 'DISABLED' WHERE id = ?", TENANT_TOGGLE);
        try {
            StatusRuntimeException denied = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub.getCatalogItem(request));
            assertEquals(DENIED_MESSAGE, denied.getStatus().getDescription());
        } finally {
            jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", TENANT_TOGGLE);
        }

        assertEquals(TENANT_TOGGLE.toString(), stub.getCatalogItem(request).getTenantId());
    }

    @Test
    void blankOrMalformedTenantIdIsInvalidArgumentForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        for (String badTenant : new String[] {"", "   ", "not-a-uuid", "12345"}) {
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.getCatalogItem(
                    GetCatalogItemRequest.newBuilder().setTenantId(badTenant).setUserId(USER_A1.toString()).setQuery("kopi").build()));
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.getOrderStatus(
                    GetOrderStatusRequest.newBuilder().setTenantId(badTenant).setUserId(USER_A1.toString())
                            .setOrderNumber(ORDER_A1_PAID).build()));
        }
    }

    // ---------------------------------------------------------------- limit handling

    @Test
    void limitZeroReturnsTheDefaultOfTen() {
        assertEquals(10, validStub().getCatalogItem(catalogRequest(TENANT_A1, "bulk item", 0))
                .getItemsCount());
    }

    @Test
    void negativeLimitFallsBackToTheDefault() {
        assertEquals(10, validStub().getCatalogItem(catalogRequest(TENANT_A1, "bulk item", -3))
                .getItemsCount());
    }

    @Test
    void hugeLimitIsClampedToFifty() {
        assertEquals(50, validStub().getCatalogItem(catalogRequest(TENANT_A1, "bulk item", 1000))
                .getItemsCount());
        assertEquals(50, validStub().getCatalogItem(catalogRequest(TENANT_A1, "bulk item", Integer.MAX_VALUE))
                .getItemsCount());
    }

    @Test
    void smallLimitsAreHonoured() {
        assertEquals(5, validStub().getCatalogItem(catalogRequest(TENANT_A1, "bulk item", 5))
                .getItemsCount());
        assertEquals(1, validStub().getCatalogItem(catalogRequest(TENANT_A1, "bulk item", 1))
                .getItemsCount());
    }

    @Test
    void emptyQueryIsAlsoBoundedByTheDefaultLimit() {
        assertEquals(10, validStub().getCatalogItem(catalogRequest(TENANT_A1, "", 0)).getItemsCount());
    }

    // ---------------------------------------------------------------- LIKE escaping

    @Test
    void percentAndUnderscoreAreNotWildcards() {
        for (String wildcardQuery : new String[] {"%", "_", "%%", "bulk%", "bulk item 00_", "_ulk item 001", "\\"}) {
            GetCatalogItemResponse response = validStub()
                    .getCatalogItem(catalogRequest(TENANT_A1, wildcardQuery, 1000));
            assertTrue(response.getItemsList().isEmpty(),
                    "query [" + wildcardQuery + "] must be treated literally but matched " + names(response));
        }
    }

    @Test
    void literalPercentInAProductNameIsFoundByEscapedQuery() {
        assertEquals(List.of("Promo 100% Off"),
                names(validStub().getCatalogItem(catalogRequest(TENANT_A2, "100%", 0))));
        assertTrue(names(validStub().getCatalogItem(catalogRequest(TENANT_A1, "100%", 0))).isEmpty(),
                "tenant A1 has no product containing 100%");
        assertTrue(names(validStub().getCatalogItem(catalogRequest(TENANT_A2, "_", 0))).isEmpty());
    }

    // ---------------------------------------------------------------- order status

    @Test
    void ownOrderIsReturnedWithStatusAndTotal() {
        GetOrderStatusResponse paid = validStub().getOrderStatus(orderRequest(TENANT_A1, ORDER_A1_PAID));
        assertEquals(TENANT_A1.toString(), paid.getTenantId());
        assertEquals(ORDER_A1_PAID, paid.getOrderNumber());
        assertEquals("COMPLETED", paid.getStatus());
        assertEquals("PAID", paid.getPaymentStatus());
        assertEquals("75000.00", paid.getTotal());
        assertFalse(paid.getCreatedAt().isEmpty());

        GetOrderStatusResponse fresh = validStub().getOrderStatus(orderRequest(TENANT_A1, ORDER_A1_OPEN));
        assertEquals("OPEN", fresh.getStatus());
        assertEquals("UNPAID", fresh.getPaymentStatus());
        assertEquals("25000.00", fresh.getTotal());
    }

    @Test
    void anotherTenantsOrderNumberIsNotFoundWithoutEchoingIt() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException crossTenant = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A2, ORDER_A1_PAID)));
        StatusRuntimeException reverse = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A1, ORDER_A2)));
        assertDoesNotLeak(crossTenant, ORDER_A1_PAID, TENANT_A1.toString(),
                TENANT_A2.toString());
        assertDoesNotLeak(reverse, ORDER_A2, TENANT_A1.toString(), TENANT_A2.toString());

        // The owner still finds its own order.
        assertEquals(ORDER_A2, stub.getOrderStatus(orderRequest(TENANT_A2, ORDER_A2)).getOrderNumber());
    }

    @Test
    void unknownOrderIsNotFoundAndLooksIdenticalToACrossTenantMiss() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException unknown = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A1, "ORD-DOES-NOT-EXIST")));
        StatusRuntimeException crossTenant = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A2, ORDER_A1_PAID)));

        assertDoesNotLeak(unknown, "ORD-DOES-NOT-EXIST");
        assertEquals(unknown.getStatus().getDescription(), crossTenant.getStatus().getDescription(),
                "a missing order and another tenant's order must be indistinguishable");
    }

    @Test
    void blankOrderNumberIsInvalidArgument() {
        assertCode(Status.Code.INVALID_ARGUMENT, () -> validStub().getOrderStatus(orderRequest(TENANT_A1, "  ")));
    }

    // ---------------------------------------------------------------- member and outlet scoping (user_id)

    @Test
    void missingOrMalformedUserIdIsInvalidArgumentForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        for (String badUser : new String[] {"", "   ", "not-a-uuid"}) {
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.getCatalogItem(GetCatalogItemRequest.newBuilder()
                    .setTenantId(TENANT_A1.toString()).setUserId(badUser).setQuery("kopi").build()));
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.getOrderStatus(GetOrderStatusRequest.newBuilder()
                    .setTenantId(TENANT_A1.toString()).setUserId(badUser).setOrderNumber(ORDER_A1_PAID).build()));
        }
    }

    @Test
    void usersWithoutAnActiveMembershipAndOutletAccessArePermissionDeniedLikeADeniedTenant() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();
        UUID unknownUser = UUID.fromString("00000007-0000-4000-8000-000000000007");

        // outsider: active staff user with no membership; USER_A2: member of another tenant only;
        // deactivated: membership.active=false; disabled: staff user status DISABLED; unknown: no such user.
        for (UUID caller : new UUID[] {USER_OUTSIDER, USER_A2, USER_A1_DEACTIVATED, USER_A1_DISABLED, unknownUser}) {
            StatusRuntimeException catalogError = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub.getCatalogItem(catalogRequest(TENANT_A1, caller, "kopi", 0)));
            StatusRuntimeException orderError = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub.getOrderStatus(orderRequest(TENANT_A1, caller, ORDER_A1_PAID)));
            assertEquals(DENIED_MESSAGE, catalogError.getStatus().getDescription(), "caller " + caller);
            assertEquals(DENIED_MESSAGE, orderError.getStatus().getDescription(), "caller " + caller);
            assertDoesNotLeak(catalogError, caller.toString(), TENANT_A1.toString());
            assertDoesNotLeak(orderError, caller.toString(), ORDER_A1_PAID);
        }
    }

    @Test
    void deactivatingTheMembershipStopsGrpcAccessImmediately() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();
        GetCatalogItemRequest request = catalogRequest(TENANT_TOGGLE, USER_TOGGLE, "", 0);

        assertEquals(TENANT_TOGGLE.toString(), stub.getCatalogItem(request).getTenantId());

        jdbc.update("UPDATE tenant_memberships SET active = false WHERE user_id = ?", USER_TOGGLE);
        try {
            assertCode(Status.Code.PERMISSION_DENIED, () -> stub.getCatalogItem(request));
        } finally {
            jdbc.update("UPDATE tenant_memberships SET active = true WHERE user_id = ?", USER_TOGGLE);
        }

        assertEquals(TENANT_TOGGLE.toString(), stub.getCatalogItem(request).getTenantId());
    }

    @Test
    void catalogIsLimitedToTheOutletsOfTheCaller() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        assertEquals(List.of("Kopi Susu"), names(stub.getCatalogItem(catalogRequest(TENANT_A1, USER_A1, "kopi", 0))),
                "outlet 1 user must not see outlet 2 products");
        assertEquals(List.of("Kopi Cabang Dua"),
                names(stub.getCatalogItem(catalogRequest(TENANT_A1, USER_A1_OUTLET2, "kopi", 0))),
                "outlet 2 user must not see outlet 1 products");
        assertEquals("21000.00", stub.getCatalogItem(catalogRequest(TENANT_A1, USER_A1_OUTLET2, "kopi", 0))
                .getItems(0).getPriceAmount());
    }

    @Test
    void orderOfAnotherOutletIsNotFoundForTheCallerAndFoundForItsOwnOutlet() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException otherOutlet = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A1, USER_A1, ORDER_A1_OUTLET2)));
        StatusRuntimeException missing = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A1, USER_A1, "ORD-DOES-NOT-EXIST")));
        assertDoesNotLeak(otherOutlet, ORDER_A1_OUTLET2);
        assertEquals(missing.getStatus().getDescription(), otherOutlet.getStatus().getDescription(),
                "an order of another outlet must look like a missing order");
        assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(TENANT_A1, USER_A1_OUTLET2, ORDER_A1_PAID)));

        GetOrderStatusResponse own = stub.getOrderStatus(orderRequest(TENANT_A1, USER_A1_OUTLET2, ORDER_A1_OUTLET2));
        assertEquals(ORDER_A1_OUTLET2, own.getOrderNumber());
        assertEquals("12500.50", own.getTotal(), "money is a plain decimal string with scale 2");
    }

    // ---------------------------------------------------------------- reflection

    @Test
    void serverReflectionV1AlphaIsNotExposed() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Object> response = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        io.grpc.reflection.v1alpha.ServerReflectionGrpc.ServerReflectionStub reflection =
                io.grpc.reflection.v1alpha.ServerReflectionGrpc.newStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(validHeaders()));

        StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionRequest> requests = reflection.serverReflectionInfo(
                new StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse>() {
                    @Override
                    public void onNext(io.grpc.reflection.v1alpha.ServerReflectionResponse value) {
                        response.set(value);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error.set(throwable);
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });
        requests.onNext(io.grpc.reflection.v1alpha.ServerReflectionRequest.newBuilder().setListServices("").build());

        assertTrue(done.await(10, TimeUnit.SECONDS), "reflection call did not finish");
        assertNull(response.get(), "reflection must not return the service list");
        assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(error.get()).getCode());
    }

    @Test
    void serverReflectionV1IsNotExposed()throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Object> response = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        io.grpc.reflection.v1.ServerReflectionGrpc.ServerReflectionStub reflection =
                io.grpc.reflection.v1.ServerReflectionGrpc.newStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(validHeaders()));

        StreamObserver<io.grpc.reflection.v1.ServerReflectionRequest> requests = reflection.serverReflectionInfo(
                new StreamObserver<io.grpc.reflection.v1.ServerReflectionResponse>() {
                    @Override
                    public void onNext(io.grpc.reflection.v1.ServerReflectionResponse value) {
                        response.set(value);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error.set(throwable);
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });
        requests.onNext(io.grpc.reflection.v1.ServerReflectionRequest.newBuilder().setListServices("").build());

        assertTrue(done.await(10, TimeUnit.SECONDS), "reflection call did not finish");
        assertNull(response.get(), "reflection must not return the service list");
        assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(error.get()).getCode());
    }

    // ---------------------------------------------------------------- client helpers

    private CashierServiceGrpc.CashierServiceBlockingStub validStub() {
        return stub(GRPC_TEST_SERVICE_KEY);
    }

    /** A blocking stub that sends the given service key; a null key sends no header at all. */
    private CashierServiceGrpc.CashierServiceBlockingStub stub(String serviceKey) {
        CashierServiceGrpc.CashierServiceBlockingStub stub = CashierServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(15, TimeUnit.SECONDS);
        if (serviceKey == null) {
            return stub;
        }
        Metadata headers = new Metadata();
        headers.put(SERVICE_KEY_HEADER, serviceKey);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static Metadata validHeaders() {
        Metadata headers = new Metadata();
        headers.put(SERVICE_KEY_HEADER, GRPC_TEST_SERVICE_KEY);
        return headers;
    }

    /** The caller is the default member of the tenant; tenants without seeded members use USER_A1 (denied earlier by the tenant check). */
    private static UUID memberOf(UUID tenantId) {
        if (TENANT_A2.equals(tenantId)) {
            return USER_A2;
        }
        return TENANT_TOGGLE.equals(tenantId) ? USER_TOGGLE : USER_A1;
    }

    private static GetCatalogItemRequest catalogRequest(UUID tenantId, String query, int limit) {
        return catalogRequest(tenantId, memberOf(tenantId), query, limit);
    }

    private static GetCatalogItemRequest catalogRequest(UUID tenantId, UUID userId, String query, int limit) {
        return GetCatalogItemRequest.newBuilder().setTenantId(tenantId.toString()).setUserId(userId.toString())
                .setQuery(query).setLimit(limit).build();
    }

    private static GetOrderStatusRequest orderRequest(UUID tenantId, String orderNumber) {
        return orderRequest(tenantId, memberOf(tenantId), orderNumber);
    }

    private static GetOrderStatusRequest orderRequest(UUID tenantId, UUID userId, String orderNumber) {
        return GetOrderStatusRequest.newBuilder().setTenantId(tenantId.toString()).setUserId(userId.toString())
                .setOrderNumber(orderNumber).build();
    }

    private static List<String> names(GetCatalogItemResponse response) {
        List<String> names = new ArrayList<>();
        for (CatalogItem item : response.getItemsList()) {
            names.add(item.getName());
        }
        return names;
    }

    private static StatusRuntimeException assertCode(Status.Code expected, Executable call) {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call);
        assertEquals(expected, error.getStatus().getCode(), "unexpected status: " + error.getStatus());
        return error;
    }

    private static void assertDoesNotLeak(StatusRuntimeException error, String... forbidden) {
        String description = error.getStatus().getDescription() == null ? "" : error.getStatus().getDescription();
        for (String value : forbidden) {
            if (!value.isEmpty()) {
                assertFalse(description.contains(value), "status description leaks [" + value + "]: " + description);
            }
        }
    }

    // ---------------------------------------------------------------- seed data

    /** Reference data that migrations do not seed (same row the other integration tests rely on for FNB). */
    private void ensureBusinessType(String code, String name) {
        jdbc.update("INSERT INTO business_types (id, code, name, is_active, created_at) "
                + "VALUES (gen_random_uuid(), ?, ?, true, now()) ON CONFLICT (code) DO NOTHING", code, name);
    }

    /** Inserts a tenant; a null business type code leaves business_type_id empty. */
    private void seedTenant(UUID tenantId, String name, String status, String businessTypeCode) {
        UUID businessTypeId = businessTypeCode == null ? null
                : jdbc.queryForObject("SELECT id FROM business_types WHERE code = ?", UUID.class, businessTypeCode);
        jdbc.update("INSERT INTO tenants (id, name, currency, time_zone, tax_rate, service_charge_rate, status, "
                + "business_type_id, created_at, updated_at, version) "
                + "VALUES (?, ?, 'IDR', 'Asia/Jakarta', 0, 0, ?, ?, now(), now(), 0)",
                tenantId, name, status, businessTypeId);
        // The AI RPCs need an effective PREMIUM plan; plan gating itself is tested in OwnerInsightIntegrationTest.
        jdbc.update("INSERT INTO subscriptions (id, tenant_id, plan_code, status, starts_at, created_at, updated_at, "
                + "version) VALUES (gen_random_uuid(), ?, 'PREMIUM', 'ACTIVE', now() - interval '1 minute', now(), now(), 0)",
                tenantId);
    }

    private UUID seedOutlet(UUID tenantId, String name) {
        UUID outletId = UUID.randomUUID();
        jdbc.update("INSERT INTO outlets (id, tenant_id, name, time_zone, active, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'Asia/Jakarta', true, now(), now(), 0)", outletId, tenantId, name);
        return outletId;
    }

    private UUID seedCategory(UUID tenantId, UUID outletId, String name) {
        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, tenant_id, outlet_id, name, archived, display_order, created_at, "
                + "updated_at, version) VALUES (?, ?, ?, ?, false, 1, now(), now(), 0)",
                categoryId, tenantId, outletId, name);
        return categoryId;
    }

    private UUID seedProduct(UUID tenantId, UUID outletId, UUID categoryId, String name, long price,
            boolean available, boolean archived, int displayOrder) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, outlet_id, category_id, name, price, available, archived, "
                + "display_order, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 0)",
                productId, tenantId, outletId, categoryId, name, BigDecimal.valueOf(price), available, archived,
                displayOrder);
        return productId;
    }

    private void seedOrder(UUID tenantId, UUID outletId, String orderNumber, String status, String paymentStatus,
            double total) {
        jdbc.update("INSERT INTO sales_orders (id, tenant_id, outlet_id, order_number, idempotency_key, status, "
                + "payment_status, service_type, source, subtotal, discount_amount, tax_amount, "
                + "service_charge_amount, tax_rate_snapshot, service_charge_rate_snapshot, total, created_at, "
                + "updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, 'TAKEAWAY', 'POS', ?, 0, 0, 0, 0, 0, ?, now(), "
                + "now(), 0)", UUID.randomUUID(), tenantId, outletId, orderNumber, "idem-" + orderNumber, status,
                paymentStatus, BigDecimal.valueOf(total), BigDecimal.valueOf(total));
    }

    /** Staff users with their membership and outlet access; the gRPC caller must be one of these. */
    private void seedStaffAccess() {
        seedMember(USER_A1, "ACTIVE", TENANT_A1, true, a1Outlet);
        seedMember(USER_A1_OUTLET2, "ACTIVE", TENANT_A1, true, a1Outlet2);
        seedMember(USER_A1_DEACTIVATED, "ACTIVE", TENANT_A1, false, a1Outlet);
        seedMember(USER_A1_DISABLED, "DISABLED", TENANT_A1, true, a1Outlet);
        seedMember(USER_A2, "ACTIVE", TENANT_A2, true, a2Outlet);
        seedMember(USER_TOGGLE, "ACTIVE", TENANT_TOGGLE, true, toggleOutlet);
        seedStaffUser(USER_OUTSIDER, "ACTIVE");
    }

    private void seedStaffUser(UUID userId, String status) {
        jdbc.update("INSERT INTO staff_users (id, email, password_hash, full_name, status, created_at, updated_at, version) "
                + "VALUES (?, ?, 'unused-hash', ?, ?, now(), now(), 0)", userId, "grpc-" + userId + "@example.com",
                "Staff " + userId, status);
    }

    private void seedMember(UUID userId, String userStatus, UUID tenantId, boolean membershipActive, UUID outletId) {
        seedStaffUser(userId, userStatus);
        UUID membershipId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant_memberships (id, tenant_id, user_id, role, active, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, 'CASHIER', ?, now(), now(), 0)", membershipId, tenantId, userId, membershipActive);
        jdbc.update("INSERT INTO membership_outlet_access (id, membership_id, outlet_id, tenant_id, created_at) "
                + "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), membershipId, outletId, tenantId);
    }

    private void seedTenantA1() {
        UUID tenant = TENANT_A1;
        seedTenant(tenant, "Warung A1", "ACTIVE", "FNB");
        UUID outlet = seedOutlet(tenant, "Outlet A1");
        a1Outlet = outlet;
        UUID drinks = seedCategory(tenant, outlet, "Minuman");
        UUID snacks = seedCategory(tenant, outlet, "Snack");
        a1KopiSusuId = seedProduct(tenant, outlet, drinks, "Kopi Susu", 18000, true, false, 1);
        seedProduct(tenant, outlet, drinks, "Kopi Arsip", 15000, true, true, 2);
        seedProduct(tenant, outlet, drinks, "Kopi Habis", 15000, false, false, 3);
        for (int index = 1; index <= BULK_PRODUCTS; index++) {
            seedProduct(tenant, outlet, snacks, String.format("Bulk Item %03d", index), 5000L + index, true, false,
                    10 + index);
        }
        seedOrder(tenant, outlet, ORDER_A1_PAID, "COMPLETED", "PAID", 75000);
        seedOrder(tenant, outlet, ORDER_A1_OPEN, "OPEN", "UNPAID", 25000);

        a1Outlet2 = seedOutlet(tenant, "Outlet A1 Dua");
        UUID drinks2 = seedCategory(tenant, a1Outlet2, "Minuman");
        seedProduct(tenant, a1Outlet2, drinks2, "Kopi Cabang Dua", 21000, true, false, 1);
        seedOrder(tenant, a1Outlet2, ORDER_A1_OUTLET2, "OPEN", "UNPAID", 12500.5);
    }

    private void seedTenantA2() {
        UUID tenant = TENANT_A2;
        seedTenant(tenant, "Warung A2", "ACTIVE", "FNB");
        UUID outlet = seedOutlet(tenant, "Outlet A2");
        a2Outlet = outlet;
        UUID drinks = seedCategory(tenant, outlet, "Minuman");
        seedProduct(tenant, outlet, drinks, "Kopi Tetangga", 20000, true, false, 1);
        seedProduct(tenant, outlet, drinks, "Promo 100% Off", 9000, true, false, 2);
        seedOrder(tenant, outlet, ORDER_A2, "OPEN", "UNPAID", 40000);
    }

    /** A tenant with real data that is disabled: its data must stay unreachable through gRPC. */
    private void seedTenantDisabled() {
        UUID tenant = TENANT_DISABLED;
        seedTenant(tenant, "Warung C", "DISABLED", "FNB");
        UUID outlet = seedOutlet(tenant, "Outlet C");
        UUID drinks = seedCategory(tenant, outlet, "Minuman");
        seedProduct(tenant, outlet, drinks, "Kopi Tenant C", 22000, true, false, 1);
        seedOrder(tenant, outlet, ORDER_C, "OPEN", "UNPAID", 30000);
    }
}
