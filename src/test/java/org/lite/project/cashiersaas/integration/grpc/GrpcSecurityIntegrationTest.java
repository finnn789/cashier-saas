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
 * service-key authentication, server-side tenant allowlist, input limits, LIKE escaping, tenant isolation of the
 * order lookup and disabled server reflection.
 *
 * <p>Tenants A1 and A2 are allowlisted (see {@link AbstractPostgresIntegrationTest}); tenant C exists in the
 * database but is not allowlisted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrpcSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Metadata.Key<String> SERVICE_KEY_HEADER =
            Metadata.Key.of("x-internal-service-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final UUID TENANT_C = UUID.fromString("c3c3c3c3-0000-4000-8000-0000000000c3");
    private static final UUID TENANT_UNKNOWN = UUID.fromString("dddddddd-0000-4000-8000-0000000000dd");

    private static final String ORDER_A1_PAID = "ORD-A1-0001";
    private static final String ORDER_A1_NEW = "ORD-A1-0002";
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

    @BeforeAll
    void setUpAll() {
        jdbc = new JdbcTemplate(dataSource);
        seedTenantA1();
        seedTenantA2();
        seedTenantC();
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
    }

    // ---------------------------------------------------------------- service key authentication

    @Test
    void missingServiceKeyIsUnauthenticatedForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = stub(null);
        GetCatalogItemRequest catalog = catalogRequest(GRPC_ALLOWED_TENANT_A1, "", 0);
        GetOrderStatusRequest order = orderRequest(GRPC_ALLOWED_TENANT_A1, ORDER_A1_PAID);

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
                    () -> stub.getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "", 0)));
            StatusRuntimeException orderError = assertCode(Status.Code.UNAUTHENTICATED,
                    () -> stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, ORDER_A1_PAID)));
            assertDoesNotLeak(catalogError, GRPC_TEST_SERVICE_KEY, GRPC_ALLOWED_TENANT_A1.toString());
            assertDoesNotLeak(orderError, GRPC_TEST_SERVICE_KEY, GRPC_ALLOWED_TENANT_A1.toString());
        }
    }

    @Test
    void keyThatIsAPrefixOfTheRealKeyIsUnauthenticated() {
        String prefix = GRPC_TEST_SERVICE_KEY.substring(0, GRPC_TEST_SERVICE_KEY.length() - 5);
        CashierServiceGrpc.CashierServiceBlockingStub stub = stub(prefix);

        assertCode(Status.Code.UNAUTHENTICATED,
                () -> stub.getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "", 0)));
        assertCode(Status.Code.UNAUTHENTICATED,
                () -> stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, ORDER_A1_PAID)));
    }

    // ---------------------------------------------------------------- tenant allowlist

    @Test
    void allowlistedTenantSeesOnlyItsOwnAvailableNonArchivedProducts() {
        GetCatalogItemResponse response = validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "kopi", 0));

        assertEquals(GRPC_ALLOWED_TENANT_A1.toString(), response.getTenantId());
        assertEquals(List.of("Kopi Susu"), names(response),
                "archived, unavailable and other tenants' 'Kopi' products must not appear");
        CatalogItem item = response.getItems(0);
        assertEquals(a1KopiSusuId.toString(), item.getId());
        assertEquals(18000L, item.getPrice());
        assertTrue(item.getIsAvailable());
        assertEquals("Minuman", item.getCategory());
    }

    @Test
    void queryIsCaseInsensitiveAndMatchesCategoryName() {
        assertEquals(List.of("Kopi Susu"),
                names(validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "  KOPI SUSU  ", 0))));
        assertEquals(List.of("Kopi Susu"),
                names(validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "minuman", 0))));
    }

    @Test
    void otherAllowlistedTenantNeverSeesTenantA1Products() {
        GetCatalogItemResponse a2 = validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A2, "kopi", 0));

        assertEquals(GRPC_ALLOWED_TENANT_A2.toString(), a2.getTenantId());
        assertEquals(List.of("Kopi Tetangga"), names(a2));
        assertTrue(names(validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A2, "bulk", 0))).isEmpty(),
                "A1 bulk products must not leak to A2");
    }

    @Test
    void realButNotAllowlistedTenantIsPermissionDeniedForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException catalogError = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_C, "", 0)));
        StatusRuntimeException orderError = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getOrderStatus(orderRequest(TENANT_C, ORDER_C)));
        assertDoesNotLeak(catalogError, TENANT_C.toString(), ORDER_C);
        assertDoesNotLeak(orderError, TENANT_C.toString(), ORDER_C);
    }

    @Test
    void nonExistentTenantIsIndistinguishableFromRealButNotAllowlistedTenant() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException real = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_C, "", 0)));
        StatusRuntimeException unknown = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub.getCatalogItem(catalogRequest(TENANT_UNKNOWN, "", 0)));

        assertEquals(real.getStatus().getDescription(), unknown.getStatus().getDescription());
    }

    @Test
    void blankOrMalformedTenantIdIsInvalidArgumentForBothRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        for (String badTenant : new String[] {"", "   ", "not-a-uuid", "12345"}) {
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.getCatalogItem(
                    GetCatalogItemRequest.newBuilder().setTenantId(badTenant).setQuery("kopi").build()));
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.getOrderStatus(
                    GetOrderStatusRequest.newBuilder().setTenantId(badTenant).setOrderNumber(ORDER_A1_PAID).build()));
        }
    }

    // ---------------------------------------------------------------- limit handling

    @Test
    void limitZeroReturnsTheDefaultOfTen() {
        assertEquals(10, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "bulk item", 0))
                .getItemsCount());
    }

    @Test
    void negativeLimitFallsBackToTheDefault() {
        assertEquals(10, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "bulk item", -3))
                .getItemsCount());
    }

    @Test
    void hugeLimitIsClampedToFifty() {
        assertEquals(50, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "bulk item", 1000))
                .getItemsCount());
        assertEquals(50, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "bulk item", Integer.MAX_VALUE))
                .getItemsCount());
    }

    @Test
    void smallLimitsAreHonoured() {
        assertEquals(5, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "bulk item", 5))
                .getItemsCount());
        assertEquals(1, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "bulk item", 1))
                .getItemsCount());
    }

    @Test
    void emptyQueryIsAlsoBoundedByTheDefaultLimit() {
        assertEquals(10, validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "", 0)).getItemsCount());
    }

    // ---------------------------------------------------------------- LIKE escaping

    @Test
    void percentAndUnderscoreAreNotWildcards() {
        for (String wildcardQuery : new String[] {"%", "_", "%%", "bulk%", "bulk item 00_", "_ulk item 001", "\\"}) {
            GetCatalogItemResponse response = validStub()
                    .getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, wildcardQuery, 1000));
            assertTrue(response.getItemsList().isEmpty(),
                    "query [" + wildcardQuery + "] must be treated literally but matched " + names(response));
        }
    }

    @Test
    void literalPercentInAProductNameIsFoundByEscapedQuery() {
        assertEquals(List.of("Promo 100% Off"),
                names(validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A2, "100%", 0))));
        assertTrue(names(validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A1, "100%", 0))).isEmpty(),
                "tenant A1 has no product containing 100%");
        assertTrue(names(validStub().getCatalogItem(catalogRequest(GRPC_ALLOWED_TENANT_A2, "_", 0))).isEmpty());
    }

    // ---------------------------------------------------------------- order status

    @Test
    void ownOrderIsReturnedWithStatusAndTotal() {
        GetOrderStatusResponse paid = validStub().getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, ORDER_A1_PAID));
        assertEquals(GRPC_ALLOWED_TENANT_A1.toString(), paid.getTenantId());
        assertEquals(ORDER_A1_PAID, paid.getOrderNumber());
        assertEquals("CONFIRMED", paid.getStatus());
        assertEquals("PAID", paid.getPaymentStatus());
        assertEquals(75000L, paid.getTotalAmount());
        assertFalse(paid.getCreatedAt().isEmpty());

        GetOrderStatusResponse fresh = validStub().getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, ORDER_A1_NEW));
        assertEquals("NEW", fresh.getStatus());
        assertEquals("UNPAID", fresh.getPaymentStatus());
        assertEquals(25000L, fresh.getTotalAmount());
    }

    @Test
    void anotherTenantsOrderNumberIsNotFoundWithoutEchoingIt() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException crossTenant = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A2, ORDER_A1_PAID)));
        StatusRuntimeException reverse = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, ORDER_A2)));
        assertDoesNotLeak(crossTenant, ORDER_A1_PAID, GRPC_ALLOWED_TENANT_A1.toString(),
                GRPC_ALLOWED_TENANT_A2.toString());
        assertDoesNotLeak(reverse, ORDER_A2, GRPC_ALLOWED_TENANT_A1.toString(), GRPC_ALLOWED_TENANT_A2.toString());

        // The owner still finds its own order.
        assertEquals(ORDER_A2, stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A2, ORDER_A2)).getOrderNumber());
    }

    @Test
    void unknownOrderIsNotFoundAndLooksIdenticalToACrossTenantMiss() {
        CashierServiceGrpc.CashierServiceBlockingStub stub = validStub();

        StatusRuntimeException unknown = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, "ORD-DOES-NOT-EXIST")));
        StatusRuntimeException crossTenant = assertCode(Status.Code.NOT_FOUND,
                () -> stub.getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A2, ORDER_A1_PAID)));

        assertDoesNotLeak(unknown, "ORD-DOES-NOT-EXIST");
        assertEquals(unknown.getStatus().getDescription(), crossTenant.getStatus().getDescription(),
                "a missing order and another tenant's order must be indistinguishable");
    }

    @Test
    void blankOrderNumberIsInvalidArgument() {
        assertCode(Status.Code.INVALID_ARGUMENT, () -> validStub().getOrderStatus(orderRequest(GRPC_ALLOWED_TENANT_A1, "  ")));
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

    private static GetCatalogItemRequest catalogRequest(UUID tenantId, String query, int limit) {
        return GetCatalogItemRequest.newBuilder().setTenantId(tenantId.toString()).setQuery(query).setLimit(limit)
                .build();
    }

    private static GetOrderStatusRequest orderRequest(UUID tenantId, String orderNumber) {
        return GetOrderStatusRequest.newBuilder().setTenantId(tenantId.toString()).setOrderNumber(orderNumber).build();
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

    private void seedTenant(UUID tenantId, String name) {
        jdbc.update("INSERT INTO tenants (id, name, currency, time_zone, tax_rate, service_charge_rate, status, "
                + "created_at, updated_at, version) VALUES (?, ?, 'IDR', 'Asia/Jakarta', 0, 0, 'ACTIVE', now(), now(), 0)",
                tenantId, name);
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
            long total) {
        jdbc.update("INSERT INTO sales_orders (id, tenant_id, outlet_id, order_number, idempotency_key, status, "
                + "payment_status, service_type, source, subtotal, discount_amount, tax_amount, "
                + "service_charge_amount, tax_rate_snapshot, service_charge_rate_snapshot, total, created_at, "
                + "updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, 'TAKEAWAY', 'POS', ?, 0, 0, 0, 0, 0, ?, now(), "
                + "now(), 0)", UUID.randomUUID(), tenantId, outletId, orderNumber, "idem-" + orderNumber, status,
                paymentStatus, BigDecimal.valueOf(total), BigDecimal.valueOf(total));
    }

    private void seedTenantA1() {
        UUID tenant = GRPC_ALLOWED_TENANT_A1;
        seedTenant(tenant, "Warung A1");
        UUID outlet = seedOutlet(tenant, "Outlet A1");
        UUID drinks = seedCategory(tenant, outlet, "Minuman");
        UUID snacks = seedCategory(tenant, outlet, "Snack");
        a1KopiSusuId = seedProduct(tenant, outlet, drinks, "Kopi Susu", 18000, true, false, 1);
        seedProduct(tenant, outlet, drinks, "Kopi Arsip", 15000, true, true, 2);
        seedProduct(tenant, outlet, drinks, "Kopi Habis", 15000, false, false, 3);
        for (int index = 1; index <= BULK_PRODUCTS; index++) {
            seedProduct(tenant, outlet, snacks, String.format("Bulk Item %03d", index), 5000L + index, true, false,
                    10 + index);
        }
        seedOrder(tenant, outlet, ORDER_A1_PAID, "CONFIRMED", "PAID", 75000);
        seedOrder(tenant, outlet, ORDER_A1_NEW, "NEW", "UNPAID", 25000);
    }

    private void seedTenantA2() {
        UUID tenant = GRPC_ALLOWED_TENANT_A2;
        seedTenant(tenant, "Warung A2");
        UUID outlet = seedOutlet(tenant, "Outlet A2");
        UUID drinks = seedCategory(tenant, outlet, "Minuman");
        seedProduct(tenant, outlet, drinks, "Kopi Tetangga", 20000, true, false, 1);
        seedProduct(tenant, outlet, drinks, "Promo 100% Off", 9000, true, false, 2);
        seedOrder(tenant, outlet, ORDER_A2, "NEW", "UNPAID", 40000);
    }

    private void seedTenantC() {
        UUID tenant = TENANT_C;
        seedTenant(tenant, "Warung C");
        UUID outlet = seedOutlet(tenant, "Outlet C");
        UUID drinks = seedCategory(tenant, outlet, "Minuman");
        seedProduct(tenant, outlet, drinks, "Kopi Tenant C", 22000, true, false, 1);
        seedOrder(tenant, outlet, ORDER_C, "NEW", "UNPAID", 30000);
    }
}
