package org.lite.project.cashiersaas.integration.grpc;

import com.cashier.grpc.v1.CashierAnomaly;
import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.GetAnomaliesRequest;
import com.cashier.grpc.v1.GetAnomaliesResponse;
import com.cashier.grpc.v1.GetRestockSuggestionsRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsResponse;
import com.cashier.grpc.v1.GetSalesSummaryRequest;
import com.cashier.grpc.v1.GetSalesSummaryResponse;
import com.cashier.grpc.v1.LabelTotal;
import com.cashier.grpc.v1.OutletSalesSummary;
import com.cashier.grpc.v1.ProductTotal;
import com.cashier.grpc.v1.RestockSuggestion;
import com.jayway.jsonpath.JsonPath;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.lite.project.cashiersaas.service.DailySummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Owner AI insight RPCs (TASK-080 M7) and the daily-summary dispatch row, against real PostgreSQL, the real
 * security chain and an in-process gRPC channel with the service key. Data is created once through the public API
 * (one owner, four cashiers, a second tenant) and the tests only read it, except the dispatch tests which onboard
 * their own tenants. The test profile keeps {@code app.daily-summary.enabled=false} and the AI base URL blank, so
 * nothing is ever sent over HTTP; the dispatch row is still created (PENDING).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerInsightIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final Metadata.Key<String> SERVICE_KEY_HEADER =
            Metadata.Key.of("x-internal-service-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final String DENIED_MESSAGE = "Tenant is not permitted for this service";
    private static final String CLOSE_NOTE = "kurang dua puluh lima ribu";

    @TestConfiguration
    static class PortConfiguration {
        @Bean
        GrpcServerPortHolder grpcServerPortHolder() {
            return new GrpcServerPortHolder();
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private GrpcServerPortHolder portHolder;
    @Autowired
    private DailySummaryService dailySummary;

    private ApiTestSupport api;
    private JdbcTemplate jdbc;
    private ManagedChannel channel;

    private MerchantFixture owner;
    private MerchantFixture main;
    private MerchantFixture quiet;
    private MerchantFixture vetA;
    private MerchantFixture vetB;
    private MerchantFixture other;
    private UUID ownerUserId;
    private UUID mainUserId;
    private UUID quietUserId;
    private UUID vetAUserId;
    private UUID vetBUserId;
    private UUID otherOwnerUserId;
    private String beras;
    private String susu;

    @BeforeAll
    void setUpAll() throws Exception {
        awaitSafeWindow();
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
        jdbc = api.jdbc();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", portHolder.port()).usePlaintext().build();
        seedMainTenant();
        other = api.onboardOwner("ins-b");
        otherOwnerUserId = ownerUserId(other);
    }

    @AfterAll
    void cleanUp() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        api.truncateTenantData();
    }

    /** All data is created "today" in the outlet zone; never straddle outlet midnight (Asia/Jakarta). */
    private static void awaitSafeWindow() throws InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(JAKARTA);
        if (now.getHour() == 23 && now.getMinute() >= 50) {
            ZonedDateTime resume = now.toLocalDate().plusDays(1).atStartOfDay(JAKARTA).plusSeconds(5);
            Thread.sleep(Duration.between(now, resume).toMillis());
        }
    }

    private static LocalDate today() {
        return LocalDate.now(JAKARTA);
    }

    // ------------------------------------------------------------------ schema

    @Test
    void migrationV10CreatesTheDispatchTableWithOneRowPerOutletAndDay() {
        assertEquals(1, count("SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND success"));
        assertEquals(1, count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'daily_summary_dispatches'"));
        assertEquals(1, count("SELECT count(*) FROM pg_constraint WHERE conname = 'uk_daily_summary_dispatches_outlet_date'"));
        assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_daily_summary_dispatches_status'"));
    }

    // ------------------------------------------------------------------ owner-only access and isolation

    @Test
    void serviceKeyIsStillRequiredForTheNewRpcs() {
        CashierServiceGrpc.CashierServiceBlockingStub noKey = stub(null);
        assertCode(Status.Code.UNAUTHENTICATED, () -> noKey.getSalesSummary(salesRequest(owner.tenantId(), ownerUserId, "2026-01-01", "2026-01-02")));
        assertCode(Status.Code.UNAUTHENTICATED, () -> noKey.getRestockSuggestions(restockRequest(owner.tenantId(), ownerUserId)));
        assertCode(Status.Code.UNAUTHENTICATED, () -> noKey.getAnomalies(anomalyRequest(owner.tenantId(), ownerUserId, "2026-01-01")));
    }

    @Test
    void cashiersAndUnknownCallersAreDeniedExactlyLikeAnUnknownTenant() {
        UUID unknownTenant = UUID.fromString("dddddddd-0000-4000-8000-0000000000dd");
        StatusRuntimeException unknown = assertCode(Status.Code.PERMISSION_DENIED,
                () -> stub().getRestockSuggestions(restockRequest(unknownTenant, ownerUserId)));
        assertEquals(DENIED_MESSAGE, unknown.getStatus().getDescription());

        UUID unknownUser = UUID.randomUUID();
        for (UUID caller : new UUID[] {mainUserId, quietUserId, vetAUserId, unknownUser}) {
            StatusRuntimeException sales = assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getSalesSummary(
                    salesRequest(owner.tenantId(), caller, today().minusDays(1).toString(), today().toString())));
            StatusRuntimeException restock = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub().getRestockSuggestions(restockRequest(owner.tenantId(), caller)));
            StatusRuntimeException anomalies = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub().getAnomalies(anomalyRequest(owner.tenantId(), caller, today().toString())));
            for (StatusRuntimeException denied : List.of(sales, restock, anomalies)) {
                assertEquals(unknown.getStatus().getDescription(), denied.getStatus().getDescription(), "caller " + caller);
                assertFalse(denied.getStatus().getDescription().contains(caller.toString()));
            }
        }
    }

    @Test
    void aRejectedCallerLearnsNothingAboutArgumentValidity() {
        // Invalid dates must not turn a cashier's PERMISSION_DENIED into INVALID_ARGUMENT.
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getSalesSummary(salesRequest(owner.tenantId(), mainUserId, "nope", "nope")));
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getAnomalies(anomalyRequest(owner.tenantId(), mainUserId, "nope")));
    }

    @Test
    void otherTenantsCannotReadEachOthersInsights() {
        // Owner B asks for tenant A and owner A asks for tenant B: both look like an unknown tenant.
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getRestockSuggestions(restockRequest(owner.tenantId(), otherOwnerUserId)));
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getSalesSummary(
                salesRequest(owner.tenantId(), otherOwnerUserId, today().minusDays(1).toString(), today().toString())));
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getAnomalies(anomalyRequest(owner.tenantId(), otherOwnerUserId, today().toString())));
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getRestockSuggestions(restockRequest(other.tenantId(), ownerUserId)));
        assertCode(Status.Code.PERMISSION_DENIED, () -> stub().getAnomalies(anomalyRequest(other.tenantId(), ownerUserId, today().toString())));
    }

    @Test
    void ownerBSeesOnlyItsOwnEmptyOutletAndNothingFromTenantA() {
        GetSalesSummaryResponse sales = stub().getSalesSummary(
                salesRequest(other.tenantId(), otherOwnerUserId, today().minusDays(1).toString(), today().plusDays(1).toString()));
        assertEquals(other.tenantId().toString(), sales.getTenantId());
        assertEquals(1, sales.getOutletsCount());
        OutletSalesSummary summary = sales.getOutlets(0);
        assertEquals(other.outletId().toString(), summary.getOutletId());
        assertEquals(0, summary.getTransactionCount());
        assertDec("0", summary.getNetSales(), "no sales leak");
        assertEquals(0, summary.getTopProductsCount());
        assertEquals(0, summary.getLabelsCount());

        GetRestockSuggestionsResponse restock = stub().getRestockSuggestions(restockRequest(other.tenantId(), otherOwnerUserId));
        assertEquals(1, restock.getOutletsCount());
        assertEquals(0, restock.getOutlets(0).getItemsCount());

        GetAnomaliesResponse anomalies = stub().getAnomalies(anomalyRequest(other.tenantId(), otherOwnerUserId, today().toString()));
        assertEquals(1, anomalies.getOutletsCount());
        assertEquals(0, anomalies.getOutlets(0).getAnomaliesCount());
    }

    @Test
    void ownerAGetsExactlyItsOwnOutlet() {
        GetSalesSummaryResponse sales = stub().getSalesSummary(
                salesRequest(owner.tenantId(), ownerUserId, today().minusDays(1).toString(), today().plusDays(1).toString()));
        assertEquals(owner.tenantId().toString(), sales.getTenantId());
        assertEquals(1, sales.getOutletsCount());
        assertEquals(owner.outletId().toString(), sales.getOutlets(0).getOutletId());
        assertEquals("Asia/Jakarta", sales.getOutlets(0).getTimeZone());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void invalidDatesAreInvalidArgumentForTheOwner() {
        String[][] badRanges = {{"", "2026-01-01"}, {"2026-01-01", ""}, {"2026-13-01", "2026-01-01"},
                {"2026-01-01", "not-a-date"}, {"2026-02-01", "2026-01-01"}, {"2025-01-01", "2026-01-02"}};
        for (String[] range : badRanges) {
            assertCode(Status.Code.INVALID_ARGUMENT,
                    () -> stub().getSalesSummary(salesRequest(owner.tenantId(), ownerUserId, range[0], range[1])));
        }
        for (String date : new String[] {"", "2026-02-30", "yesterday", "14-11-2026"}) {
            assertCode(Status.Code.INVALID_ARGUMENT, () -> stub().getAnomalies(anomalyRequest(owner.tenantId(), ownerUserId, date)));
        }
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub().getRestockSuggestions(restockRequest(owner.tenantId(), null)));
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub().getRestockSuggestions(
                GetRestockSuggestionsRequest.newBuilder().setTenantId(owner.tenantId().toString()).setUserId("not-a-uuid").build()));
    }

    // ------------------------------------------------------------------ sales summary equals the REST report

    @Test
    void salesSummaryEqualsTheRestOwnerReportAndAddsTheOwnerExtras() throws Exception {
        String start = today().minusDays(1).toString();
        String end = today().plusDays(1).toString();
        MvcResult rest = api.send(get("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/reports/sales")
                .param("startDate", start).param("endDate", end).header(AUTH, owner.bearer()), 200);
        OutletSalesSummary grpc = stub().getSalesSummary(salesRequest(owner.tenantId(), ownerUserId, start, end)).getOutlets(0);

        assertEquals(start, grpc.getStartDate());
        assertEquals(end, grpc.getEndDate());
        assertEquals(longOf(rest, "$.data.summary.transactionCount"), grpc.getTransactionCount());
        assertTrue(grpc.getTransactionCount() > 0);
        assertDec(str(rest, "$.data.summary.subtotal"), grpc.getGrossSales(), "gross sales");
        assertDec(str(rest, "$.data.summary.discountAmount"), grpc.getDiscountAmount(), "discount");
        assertDec(str(rest, "$.data.summary.netSales"), grpc.getNetSales(), "net sales");
        assertDec(str(rest, "$.data.summary.total"), grpc.getTotal(), "total");
        assertDec(str(rest, "$.data.summary.taxAmount"), grpc.getTaxAmount(), "tax");
        assertDec(str(rest, "$.data.summary.serviceChargeAmount"), grpc.getServiceChargeAmount(), "service charge");
        assertDec(str(rest, "$.data.summary.costOfGoodsSold"), grpc.getCostOfGoodsSold(), "COGS");
        assertDec(str(rest, "$.data.summary.grossProfit"), grpc.getGrossProfit(), "gross profit");
        assertEquals(longOf(rest, "$.data.summary.itemsWithoutCost"), grpc.getItemsWithoutCost());
        assertTrue(grpc.getItemsWithoutCost() > 0, "the Teh item has no recipe");
        assertEquals(longOf(rest, "$.data.summary.refundCount"), grpc.getRefundCount());
        assertEquals(1, grpc.getRefundCount());
        assertDec(str(rest, "$.data.summary.refundAmount"), grpc.getRefundAmount(), "refund amount");
        assertEquals(longOf(rest, "$.data.summary.cancellationCount"), grpc.getCancellationCount());
        assertTrue(grpc.getCancellationCount() >= 15, "5+3+2+3+3 cancelled orders were seeded");

        // Payment labels: same rows, same order.
        List<Map<String, Object>> labels = JsonPath.read(rest.getResponse().getContentAsString(), "$.data.paymentBreakdown[*]");
        assertEquals(labels.size(), grpc.getLabelsCount());
        assertTrue(labels.size() >= 2, "cash and one non-cash label");
        for (int i = 0; i < labels.size(); i++) {
            LabelTotal label = grpc.getLabels(i);
            assertEquals(String.valueOf(labels.get(i).get("method")), label.getMethod());
            Object restLabel = labels.get(i).get("methodLabel");
            assertEquals(restLabel == null ? "" : String.valueOf(restLabel), label.getLabel());
            assertEquals(Long.parseLong(String.valueOf(labels.get(i).get("transactionCount"))), label.getCount());
            assertDec(String.valueOf(labels.get(i).get("netSales")), label.getNetSales(), "label net sales");
        }

        // Top products: the first five of the REST list.
        List<Map<String, Object>> products = JsonPath.read(rest.getResponse().getContentAsString(), "$.data.topProducts[*]");
        assertEquals(Math.min(5, products.size()), grpc.getTopProductsCount());
        for (int i = 0; i < grpc.getTopProductsCount(); i++) {
            ProductTotal product = grpc.getTopProducts(i);
            assertEquals(String.valueOf(products.get(i).get("productName")), product.getName());
            assertEquals(Long.parseLong(String.valueOf(products.get(i).get("quantity"))), product.getQuantity());
            assertDec(String.valueOf(products.get(i).get("grossSales")), product.getGrossSales(), "product gross sales");
            assertDec(String.valueOf(products.get(i).get("costOfGoodsSold")), product.getCostOfGoodsSold(), "product COGS");
            assertDec(String.valueOf(products.get(i).get("grossProfit")), product.getGrossProfit(), "product gross profit");
        }
        ProductTotal nasi = grpc.getTopProductsList().stream().filter(p -> p.getName().equals("Nasi")).findFirst().orElseThrow();
        assertEquals(3, nasi.getQuantity(), "owner 2 + main cashier 1 paid; the refunded unit is excluded");
        assertDec("150", nasi.getCostOfGoodsSold(), "3 x 5 g x 10");

        // Owner extras computed from the seeded facts.
        assertEquals(count("SELECT count(*) FROM sales_orders WHERE tenant_id = ? AND discount_amount > 0 "
                + "AND payment_status = 'PAID' AND status <> 'CANCELLED'", owner.tenantId()), grpc.getDiscountedOrderCount());
        assertEquals(3, grpc.getDiscountedOrderCount());
        assertDec("7500.50", grpc.getExpensesTotal(), "petty cash recorded");
        assertDec("-25000", grpc.getCashDifferenceTotal(), "signed cash difference of the closed shift");
        assertEquals(1, grpc.getClosedShiftCount());
    }

    @Test
    void salesSummaryForADayWithoutSalesIsZero() {
        String farPast = today().minusDays(60).toString();
        OutletSalesSummary empty = stub().getSalesSummary(salesRequest(owner.tenantId(), ownerUserId, farPast, farPast)).getOutlets(0);
        assertEquals(0, empty.getTransactionCount());
        assertDec("0", empty.getNetSales(), "net sales");
        assertDec("0", empty.getExpensesTotal(), "expenses");
        assertDec("0", empty.getCashDifferenceTotal(), "cash difference");
        assertEquals(0, empty.getClosedShiftCount());
        assertEquals(0, empty.getDiscountedOrderCount());
    }

    // ------------------------------------------------------------------ restock

    @Test
    void restockSuggestionsFollowTheUsageFormulaAndTheStatusOrder() {
        List<RestockSuggestion> items = stub().getRestockSuggestions(restockRequest(owner.tenantId(), ownerUserId))
                .getOutlets(0).getItemsList();
        assertEquals(List.of("Susu", "Habis", "Beras"), items.stream().map(RestockSuggestion::getName).toList(),
                "NEGATIVE, OUT, then OK-with-suggestion; Gula (plenty, no usage) is not listed");
        assertEquals(List.of("NEGATIVE", "OUT", "OK"), items.stream().map(RestockSuggestion::getStockStatus).toList());

        // Susu: restocked 1, one Latte sold uses 3 -> on hand -2; usage 3 -> 3/14 = 0.214; 0.214 x 7 + 1 + 2 = 4.498.
        RestockSuggestion susuRow = items.get(0);
        assertEquals(susu, susuRow.getInventoryItemId());
        assertDec("-2", susuRow.getQuantityOnHand(), "Susu on hand");
        assertDec("1", susuRow.getReorderLevel(), "reorder level");
        assertDec("0.214", susuRow.getAverageDailyUsage(), "Susu daily usage");
        assertDec("4.498", susuRow.getSuggestedQuantity(), "Susu suggestion");
        assertEquals("ml", susuRow.getUnit());

        RestockSuggestion habis = items.get(1);
        assertDec("0", habis.getQuantityOnHand(), "Habis on hand");
        assertDec("0", habis.getAverageDailyUsage(), "Habis daily usage");
        assertDec("1", habis.getSuggestedQuantity(), "reorder level minus on hand");

        // Beras: usage = -(SALE + SALE_REVERSAL + WASTE) over the window, read back from the ledger.
        RestockSuggestion berasRow = items.get(2);
        BigDecimal usage = jdbc.queryForObject("SELECT -COALESCE(SUM(quantity_delta), 0) FROM inventory_movements "
                + "WHERE inventory_item_id = ?::uuid AND movement_type IN ('SALE', 'SALE_REVERSAL', 'WASTE')",
                BigDecimal.class, beras);
        assertDec("16", usage.toPlainString(), "4 sold x 5 g, minus the refunded 5 g, plus 1 g waste");
        BigDecimal onHand = jdbc.queryForObject("SELECT quantity_on_hand FROM inventory_balances WHERE inventory_item_id = ?::uuid",
                BigDecimal.class, beras);
        BigDecimal daily = usage.divide(BigDecimal.valueOf(14), 3, RoundingMode.HALF_UP);
        BigDecimal suggested = daily.multiply(BigDecimal.valueOf(7)).add(BigDecimal.ONE).subtract(onHand).setScale(3, RoundingMode.HALF_UP);
        assertDec(onHand.toPlainString(), berasRow.getQuantityOnHand(), "Beras on hand");
        assertDec(daily.toPlainString(), berasRow.getAverageDailyUsage(), "Beras daily usage");
        assertDec(suggested.toPlainString(), berasRow.getSuggestedQuantity(), "Beras suggestion");
        assertTrue(suggested.signum() > 0);
    }

    // ------------------------------------------------------------------ anomalies

    @Test
    void anomaliesFlagCashiersAboveTwiceTheirBaselineAndTheMinimums() {
        GetAnomaliesResponse response = stub().getAnomalies(anomalyRequest(owner.tenantId(), ownerUserId, today().toString()));
        assertEquals(1, response.getOutletsCount());
        assertEquals(owner.outletId().toString(), response.getOutlets(0).getOutletId());
        assertEquals(today().toString(), response.getOutlets(0).getDate());

        Set<String> actual = new HashSet<>();
        for (CashierAnomaly anomaly : response.getOutlets(0).getAnomaliesList()) {
            actual.add(anomaly.getStaffUserId() + "|" + anomaly.getKind() + "|" + norm(anomaly.getValue()) + "|"
                    + norm(anomaly.getBaselineDailyAverage()));
            assertTrue(anomaly.getFullName().startsWith("Cashier "), "owners are never listed: " + anomaly.getFullName());
        }
        Set<String> expected = Set.of(
                mainUserId + "|CANCELLATIONS|3|0",
                mainUserId + "|DISCOUNTS|3|0",
                mainUserId + "|CASH_DIFFERENCE|25000|0",
                // 44 cancellations over the previous 30 days = 1.47 a day; 3 > 2 x 1.47.
                vetAUserId + "|CANCELLATIONS|3|1.47");
        assertEquals(expected, actual, "quiet (2 < minimum 3), vetB (3 not above 2 x 1.50) and the owner (4 cancellations) are not flagged");
    }

    @Test
    void aDayWithoutActivityHasNoAnomalies() {
        for (LocalDate date : List.of(today().plusDays(1), today().minusDays(40))) {
            assertEquals(0, stub().getAnomalies(anomalyRequest(owner.tenantId(), ownerUserId, date.toString()))
                    .getOutlets(0).getAnomaliesCount(), date.toString());
        }
    }

    // ------------------------------------------------------------------ read-only

    @Test
    void theInsightRpcsWriteNothing() {
        String[] tables = {"audit_logs", "daily_summary_dispatches", "inventory_movements", "sales_orders", "payments",
                "cash_shifts", "cash_expenses"};
        int[] before = new int[tables.length];
        for (int i = 0; i < tables.length; i++) {
            before[i] = count("SELECT count(*) FROM " + tables[i]);
        }
        stub().getSalesSummary(salesRequest(owner.tenantId(), ownerUserId, today().minusDays(1).toString(), today().toString()));
        stub().getRestockSuggestions(restockRequest(owner.tenantId(), ownerUserId));
        stub().getAnomalies(anomalyRequest(owner.tenantId(), ownerUserId, today().toString()));
        for (int i = 0; i < tables.length; i++) {
            assertEquals(before[i], count("SELECT count(*) FROM " + tables[i]), tables[i]);
        }
    }

    // ------------------------------------------------------------------ daily summary dispatch row

    @Test
    void closingTheLastOpenShiftCreatesExactlyOneDispatchRowAndASecondCloseTheSameDayAddsNone() throws Exception {
        MerchantFixture shop = api.onboardOwner("ins-disp");
        MerchantFixture cashier = api.onboardCashier(shop, "ins-disp-cashier");
        String ownerShift = ApiTestSupport.read(api.openShift(shop, "0.00"), "$.data.id");
        api.openShift(cashier, "0.00");

        // Another shift is still open: no row.
        closeShift(cashier);
        assertEquals(0, dispatchRows(shop));

        // The last open shift closes: exactly one row.
        closeShift(shop);
        assertEquals(1, dispatchRows(shop));
        Map<String, Object> row = jdbc.queryForMap("SELECT \"trigger\", status, attempts, business_date, tenant_id "
                + "FROM daily_summary_dispatches WHERE outlet_id = ?", shop.outletId());
        assertEquals("SHIFT_CLOSED", row.get("trigger"));
        assertEquals("PENDING", row.get("status"), "AI base URL is blank: nothing is sent");
        assertEquals(0, ((Number) row.get("attempts")).intValue());
        assertEquals(shop.tenantId(), row.get("tenant_id"));
        LocalDate expectedDate = jdbc.queryForObject("SELECT (opened_at AT TIME ZONE 'Asia/Jakarta')::date FROM cash_shifts "
                + "WHERE id = ?::uuid", LocalDate.class, ownerShift);
        assertEquals(expectedDate, ((java.sql.Date) row.get("business_date")).toLocalDate(),
                "business date = outlet-local date of the shift's openedAt");

        // A second shift opened and closed on the same business day adds nothing.
        api.openShift(cashier, "0.00");
        closeShift(cashier);
        api.openShift(shop, "0.00");
        closeShift(shop);
        assertEquals(1, dispatchRows(shop));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM daily_summary_dispatches WHERE outlet_id = ?",
                String.class, shop.outletId()));
    }

    @Test
    void eachOutletGetsItsOwnDispatchRowAndTheBlankAiUrlMakesTheFallbackANoOp() throws Exception {
        MerchantFixture shopA = api.onboardOwner("ins-disp-a");
        MerchantFixture shopB = api.onboardOwner("ins-disp-b");
        api.openShift(shopA, "0.00");
        api.openShift(shopB, "0.00");
        closeShift(shopA);
        closeShift(shopB);
        assertEquals(1, dispatchRows(shopA));
        assertEquals(1, dispatchRows(shopB));

        int rowsBefore = count("SELECT count(*) FROM daily_summary_dispatches");
        dailySummary.runFallback(); // client disabled: no fallback rows, no retries, no HTTP
        assertEquals(rowsBefore, count("SELECT count(*) FROM daily_summary_dispatches"));
        assertEquals(0, count("SELECT count(*) FROM daily_summary_dispatches WHERE attempts > 0 OR status <> 'PENDING'"));
    }

    // ------------------------------------------------------------------ seed

    private void seedMainTenant() throws Exception {
        owner = api.onboardOwner("ins-a");
        main = api.onboardCashier(owner, "ins-main");
        quiet = api.onboardCashier(owner, "ins-quiet");
        vetA = api.onboardCashier(owner, "ins-veta");
        vetB = api.onboardCashier(owner, "ins-vetb");
        ownerUserId = ownerUserId(owner);
        mainUserId = cashierUserId(owner, "Cashier ins-main");
        quietUserId = cashierUserId(owner, "Cashier ins-quiet");
        vetAUserId = cashierUserId(owner, "Cashier ins-veta");
        vetBUserId = cashierUserId(owner, "Cashier ins-vetb");
        assertTrue(count("SELECT count(*) FROM membership_outlet_access a JOIN tenant_memberships m ON m.id = a.membership_id "
                + "WHERE m.user_id = ?", mainUserId) > 0, "the cashier does have outlet access: denial is about the role");

        beras = ingredient("Beras", "g");
        susu = ingredient("Susu", "ml");
        String habis = ingredient("Habis", "pcs");
        String gula = ingredient("Gula", "g");
        restock(beras, "20", "10");
        restock(susu, "1", "5");
        restock(gula, "500", "1");
        String category = api.createCategory(owner, "Menu");
        String nasi = recipeProduct(category, "Nasi", "10000", beras, "5");
        String latte = recipeProduct(category, "Latte", "15000", susu, "3");
        String teh = api.createProduct(owner, category, "Teh", "20000");

        // Owner: 2 Nasi, 1 Nasi refunded, 1 Latte (Susu goes negative), 4 cancellations, 1 gram of waste.
        api.ensureShift(owner);
        api.payCashExact(owner, api.createPosOrder(owner, nasi, 2));
        String refunded = api.createPosOrder(owner, nasi, 1);
        api.payCashExact(owner, refunded);
        api.send(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/orders/" + refunded + "/refund")
                .header(AUTH, owner.bearer()).header("Idempotency-Key", "refund-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Salah pesan\"}"), 200);
        api.payCashExact(owner, api.createPosOrder(owner, latte, 1));
        cancelOrders(owner, teh, 4);
        api.send(post("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/inventory/items/" + beras + "/movements")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"WASTE\",\"quantity\":1,\"reason\":\"Tumpah\",\"idempotencyKey\":\"mv-" + UUID.randomUUID() + "\"}"), 201);

        // Main cashier: 1 non-cash Nasi, 1 cash Teh, 3 discounted Teh, 3 cancellations, 7500.50 petty cash, shift closed 25000 short.
        api.ensureShift(main);
        api.payNonCash(main, api.createPosOrder(main, nasi, 1), api.firstActiveLabelId(main));
        api.payCashExact(main, api.createPosOrder(main, teh, 1));
        for (int i = 0; i < 3; i++) {
            String order = api.createPosOrder(main, teh, 1);
            api.send(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/orders/" + order + "/discount")
                    .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"NOMINAL\",\"value\":1000,\"reason\":\"Promo\"}"), 200);
            api.payCashExact(main, order);
        }
        cancelOrders(main, teh, 3);
        expense(main, "5000", "Es batu");
        expense(main, "2500.50", "Parkir");
        MvcResult current = api.send(get("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/shifts/current")
                .header(AUTH, main.bearer()), 200);
        BigDecimal counted = new BigDecimal(ApiTestSupport.read(current, "$.data.expectedCash")).subtract(new BigDecimal("25000"));
        api.send(post("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/shifts/current/close").header(AUTH, main.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":" + counted.toPlainString() + ",\"note\":\"" + CLOSE_NOTE + "\"}"), 200);

        // Quiet: 2 cancellations (below the minimum of 3). Veterans: 3 cancellations today and a 30-day history.
        cancelOrders(quiet, teh, 2);
        cancelOrders(vetA, teh, 3);
        cancelOrders(vetB, teh, 3);
        cloneCancellationAudit(vetAUserId, 44);
        cloneCancellationAudit(vetBUserId, 45);
    }

    private String ingredient(String name, String unit) throws Exception {
        return ApiTestSupport.read(api.send(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/inventory/items")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"reorderLevel\":1}"), 201), "$.data.id");
    }

    private void restock(String itemId, String quantity, String unitCost) throws Exception {
        api.send(post("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/inventory/items/" + itemId + "/movements")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"RESTOCK\",\"quantity\":" + quantity + ",\"reason\":\"Belanja\",\"unitCost\":" + unitCost
                        + ",\"idempotencyKey\":\"mv-" + UUID.randomUUID() + "\"}"), 201);
    }

    private String recipeProduct(String category, String name, String price, String itemId, String perUnit) throws Exception {
        String productId = api.createProduct(owner, category, name, price);
        api.send(put("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/products/" + productId + "/recipe")
                .header(AUTH, owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":\"" + itemId + "\",\"quantityPerUnit\":" + perUnit + "}]}"), 200);
        return productId;
    }

    /** Creates {@code times} unpaid orders for the actor and cancels each one (audit FNB_ORDER_CANCELLED by the actor). */
    private void cancelOrders(MerchantFixture actor, String productId, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            String orderId = api.createPosOrder(actor, productId, 1);
            api.send(post("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/orders/" + orderId + "/cancel")
                    .header(AUTH, actor.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Salah pesan\"}"), 200);
        }
    }

    private void expense(MerchantFixture actor, String amount, String description) throws Exception {
        api.send(post("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/shifts/current/expenses")
                .header(AUTH, actor.bearer()).header("Idempotency-Key", "exp-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"description\":\"" + description + "\"}"), 201);
    }

    /** History for the baseline: copies one real cancellation audit row of the user 5 days back, {@code copies} times. */
    private void cloneCancellationAudit(UUID userId, int copies) {
        jdbc.update("INSERT INTO audit_logs (created_at, actor_staff_user_id, entity_id, id, outlet_id, tenant_id, action, "
                + "entity_type, reason, details) SELECT now() - interval '5 days', a.actor_staff_user_id, a.entity_id, "
                + "gen_random_uuid(), a.outlet_id, a.tenant_id, a.action, a.entity_type, a.reason, a.details "
                + "FROM (SELECT * FROM audit_logs WHERE action = 'FNB_ORDER_CANCELLED' AND actor_staff_user_id = ? LIMIT 1) a "
                + "CROSS JOIN generate_series(1, ?) g", userId, copies);
    }

    private void closeShift(MerchantFixture actor) throws Exception {
        api.send(post("/api/v1/cashier/outlets/" + actor.outletId() + "/fnb/shifts/current/close")
                .header(AUTH, actor.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":0}"), 200);
    }

    private int dispatchRows(MerchantFixture shop) {
        return count("SELECT count(*) FROM daily_summary_dispatches WHERE tenant_id = ? AND outlet_id = ?",
                shop.tenantId(), shop.outletId());
    }

    private UUID ownerUserId(MerchantFixture shop) {
        return jdbc.queryForObject("SELECT user_id FROM tenant_memberships WHERE tenant_id = ? AND role = 'OWNERMERCHANT'",
                UUID.class, shop.tenantId());
    }

    private UUID cashierUserId(MerchantFixture shop, String fullName) {
        return jdbc.queryForObject("SELECT m.user_id FROM tenant_memberships m JOIN staff_users u ON u.id = m.user_id "
                + "WHERE m.tenant_id = ? AND m.role = 'CASHIER' AND u.full_name = ?", UUID.class, shop.tenantId(), fullName);
    }

    // ------------------------------------------------------------------ gRPC helpers

    private CashierServiceGrpc.CashierServiceBlockingStub stub() {
        return stub(GRPC_TEST_SERVICE_KEY);
    }

    private CashierServiceGrpc.CashierServiceBlockingStub stub(String serviceKey) {
        CashierServiceGrpc.CashierServiceBlockingStub stub = CashierServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS);
        if (serviceKey == null) {
            return stub;
        }
        Metadata headers = new Metadata();
        headers.put(SERVICE_KEY_HEADER, serviceKey);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static GetSalesSummaryRequest salesRequest(UUID tenantId, UUID userId, String start, String end) {
        return GetSalesSummaryRequest.newBuilder().setTenantId(tenantId.toString()).setUserId(userId.toString())
                .setStartDate(start).setEndDate(end).build();
    }

    /** A null user id sends an empty user_id. */
    private static GetRestockSuggestionsRequest restockRequest(UUID tenantId, UUID userId) {
        return GetRestockSuggestionsRequest.newBuilder().setTenantId(tenantId.toString())
                .setUserId(userId == null ? "" : userId.toString()).build();
    }

    private static GetAnomaliesRequest anomalyRequest(UUID tenantId, UUID userId, String date) {
        return GetAnomaliesRequest.newBuilder().setTenantId(tenantId.toString()).setUserId(userId.toString())
                .setDate(date).build();
    }

    private static StatusRuntimeException assertCode(Status.Code expected, Executable call) {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call);
        assertEquals(expected, error.getStatus().getCode(), "unexpected status: " + error.getStatus());
        return error;
    }

    // ------------------------------------------------------------------ value helpers

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private static String str(MvcResult result, String path) throws Exception {
        return ApiTestSupport.read(result, path);
    }

    private static long longOf(MvcResult result, String path) throws Exception {
        return Long.parseLong(str(result, path));
    }

    private static void assertDec(String expected, String actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                message + ": expected " + expected + " but was " + actual);
    }

    /** "25000.00" and "25000" compare equal; "0.00" and "0" too. */
    private static String norm(String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

}
