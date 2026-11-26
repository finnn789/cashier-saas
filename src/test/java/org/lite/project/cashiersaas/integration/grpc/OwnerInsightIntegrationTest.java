package org.lite.project.cashiersaas.integration.grpc;

import com.cashier.grpc.v1.CashierAnomaly;
import com.cashier.grpc.v1.CashierServiceGrpc;
import com.cashier.grpc.v1.ConsumeAiQuestionRequest;
import com.cashier.grpc.v1.ConsumeAiQuestionResponse;
import com.cashier.grpc.v1.GetAnomaliesRequest;
import com.cashier.grpc.v1.GetAnomaliesResponse;
import com.cashier.grpc.v1.GetCatalogItemRequest;
import com.cashier.grpc.v1.GetLeakageReportRequest;
import com.cashier.grpc.v1.GetLeakageReportResponse;
import com.cashier.grpc.v1.GetMenuMarginsRequest;
import com.cashier.grpc.v1.GetMenuMarginsResponse;
import com.cashier.grpc.v1.GetOrderStatusRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsRequest;
import com.cashier.grpc.v1.GetRestockSuggestionsResponse;
import com.cashier.grpc.v1.GetSalesSummaryRequest;
import com.cashier.grpc.v1.GetSalesSummaryResponse;
import com.cashier.grpc.v1.LabelTotal;
import com.cashier.grpc.v1.LeakageLine;
import com.cashier.grpc.v1.MenuMargin;
import com.cashier.grpc.v1.OutletLeakage;
import com.cashier.grpc.v1.OutletSalesSummary;
import com.cashier.grpc.v1.ProductTotal;
import com.cashier.grpc.v1.RecordAiUsageRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    // ------------------------------------------------------------------ owner-only access and isolation

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
            StatusRuntimeException leakage = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub().getLeakageReport(leakageRequest(owner.tenantId(), caller, "", "")));
            StatusRuntimeException margins = assertCode(Status.Code.PERMISSION_DENIED,
                    () -> stub().getMenuMargins(marginRequest(owner.tenantId(), caller, "")));
            for (StatusRuntimeException denied : List.of(sales, restock, anomalies, leakage, margins)) {
                assertEquals(unknown.getStatus().getDescription(), denied.getStatus().getDescription(), "caller " + caller);
                assertFalse(denied.getStatus().getDescription().contains(caller.toString()));
            }
        }
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

    // ------------------------------------------------------------------ validation

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
        assertEquals(api.count("SELECT count(*) FROM sales_orders WHERE tenant_id = ? AND discount_amount > 0 "
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

    // ------------------------------------------------------------------ leakage and menu margin (TASK-084/085)

    @Test
    void leakageReportReturnsTheFlaggedShortagesOfTheStockCountsInTheRange() throws Exception {
        UUID gula = jdbc.queryForObject("SELECT id FROM inventory_items WHERE tenant_id = ? AND name = 'Gula'", UUID.class, owner.tenantId());
        api.send(post("/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/inventory/stock-counts").header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"count-" + UUID.randomUUID()
                        + "\",\"lines\":[{\"inventoryItemId\":\"" + gula + "\",\"countedQuantity\":495}]}"), 201);

        GetLeakageReportResponse response = stub().getLeakageReport(
                leakageRequest(owner.tenantId(), ownerUserId, today().minusDays(1).toString(), today().plusDays(1).toString()));

        assertEquals(owner.tenantId().toString(), response.getTenantId());
        assertEquals(1, response.getOutletsCount());
        OutletLeakage outlet = response.getOutlets(0);
        assertEquals(owner.outletId().toString(), outlet.getOutletId());
        assertEquals(1, outlet.getStockCountCount());
        assertEquals(1, outlet.getFlaggedCount());
        assertDec("5", outlet.getTotalLoss(), "500 counted down to 495 at average 1");
        assertEquals(1, outlet.getLinesCount());
        LeakageLine line = outlet.getLines(0);
        assertEquals("Gula", line.getName());
        assertEquals("g", line.getUnit());
        assertDec("0", line.getTheoreticalUsage(), "no recipe uses it");
        assertDec("5", line.getShortage(), "shortage");
        assertEquals("", line.getVariancePct(), "no usage: the percentage is empty");
        assertDec("5", line.getLossAmount(), "loss");
        assertDec("0", line.getWaste(), "waste");
        assertEquals(1, stub().getLeakageReport(leakageRequest(owner.tenantId(), ownerUserId, "", "")).getOutlets(0).getStockCountCount(),
                "an empty range defaults to the last 30 days");
        assertEquals(0, stub().getLeakageReport(leakageRequest(owner.tenantId(), ownerUserId, today().minusDays(40).toString(),
                today().minusDays(40).toString())).getOutletsCount(), "outlets without a count in the range are omitted");
    }

    @Test
    void menuMarginsListOnlyBelowTargetMenusUnlessAMaximumMarginIsGiven() {
        GetMenuMarginsResponse belowTarget = stub().getMenuMargins(marginRequest(owner.tenantId(), ownerUserId, ""));
        assertEquals(1, belowTarget.getOutletsCount());
        assertEquals(owner.outletId().toString(), belowTarget.getOutlets(0).getOutletId());
        assertDec("60", belowTarget.getOutlets(0).getTargetMarginPct(), "default target");
        assertEquals(0, belowTarget.getOutlets(0).getMenusCount(), "Nasi and Latte are far above the target");

        List<MenuMargin> upTo997 = stub().getMenuMargins(marginRequest(owner.tenantId(), ownerUserId, "99.70")).getOutlets(0).getMenusList();
        assertEquals(List.of("Nasi"), upTo997.stream().map(MenuMargin::getName).toList(), "Latte is 99.90 and Teh has no recipe");
        MenuMargin nasi = upTo997.get(0);
        assertEquals("OK", nasi.getStatus());
        assertDec("10000", nasi.getPrice(), "price");
        assertDec("50", nasi.getHpp(), "5 g x 10");
        assertDec("99.5", nasi.getMarginPct(), "(10000 - 50) / 10000");
        assertEquals("", nasi.getSuggestedPrice(), "only below-target menus get a suggestion");
        assertEquals(List.of("Latte", "Nasi"), stub().getMenuMargins(marginRequest(owner.tenantId(), ownerUserId, "100"))
                .getOutlets(0).getMenusList().stream().map(MenuMargin::getName).toList(), "the maximum is inclusive");
    }

    // ------------------------------------------------------------------ plan gate and AI question quota (TASK-082)

    @ParameterizedTest
    @EnumSource(value = PlanCode.class, names = {"FREETIER", "PRO"})
    void everyAiRpcIsRefusedBelowPremiumAndTheQuotaIsNotTouched(PlanCode plan) throws Exception {
        MerchantFixture shop = api.onboardOwner("ins-plan-" + plan, plan);
        UUID shopOwner = ownerUserId(shop);
        String tenant = shop.tenantId().toString();

        for (Executable call : new Executable[] {
                () -> stub().getCatalogItem(GetCatalogItemRequest.newBuilder().setTenantId(tenant).setUserId(shopOwner.toString()).build()),
                () -> stub().getOrderStatus(GetOrderStatusRequest.newBuilder().setTenantId(tenant).setUserId(shopOwner.toString())
                        .setOrderNumber("X").build()),
                () -> stub().getSalesSummary(salesRequest(shop.tenantId(), shopOwner, today().toString(), today().toString())),
                () -> stub().getRestockSuggestions(restockRequest(shop.tenantId(), shopOwner)),
                () -> stub().getAnomalies(anomalyRequest(shop.tenantId(), shopOwner, today().toString())),
                () -> stub().getLeakageReport(leakageRequest(shop.tenantId(), shopOwner, "", "")),
                () -> stub().getMenuMargins(marginRequest(shop.tenantId(), shopOwner, ""))}) {
            StatusRuntimeException refused = assertCode(Status.Code.FAILED_PRECONDITION, call);
            assertEquals("PLAN_PREMIUM_REQUIRED", refused.getStatus().getDescription());
        }

        ConsumeAiQuestionResponse consume = consume(shop.tenantId(), shopOwner);
        assertFalse(consume.getAllowed());
        assertEquals("PLAN_PREMIUM_REQUIRED", consume.getReason());
        assertEquals(0, api.count("SELECT count(*) FROM ai_usage_periods WHERE tenant_id = ?", shop.tenantId()));
    }

    @Test
    void consumingAnAiQuestionIsOwnerOnlyAndADeniedCallerIsNeverCounted() {
        jdbc.update("DELETE FROM ai_usage_periods WHERE tenant_id IN (?, ?)", owner.tenantId(), other.tenantId());

        assertCode(Status.Code.PERMISSION_DENIED, () -> consume(owner.tenantId(), mainUserId));
        assertCode(Status.Code.PERMISSION_DENIED, () -> consume(other.tenantId(), ownerUserId));
        assertEquals(0, api.count("SELECT count(*) FROM ai_usage_periods WHERE tenant_id IN (?, ?)", owner.tenantId(), other.tenantId()));
    }

    /**
     * Boundaries of the monthly (300 + bonus) and daily (40) limits, the 80% warning and the day reset, against the real
     * upsert. A refused question changes nothing.
     */
    @ParameterizedTest(name = "used={0} bonus={1} dayQuestions={2} dayOffset={3}")
    @CsvSource({
            // used, bonus, dayQuestions, dayOffset, allowed, reason, usedAfter, limit, warn, dayQuestionsAfter
            "238, 0, 0, 0, true, '', 239, 300, false, 1",
            "239, 0, 0, 0, true, '', 240, 300, true, 1",
            "299, 0, 0, 0, true, '', 300, 300, true, 1",
            "300, 0, 0, 0, false, MONTHLY_QUOTA_EXHAUSTED, 300, 300, true, 0",
            "300, 200, 0, 0, true, '', 301, 500, false, 1",
            "10, 0, 39, 0, true, '', 11, 300, false, 40",
            "10, 0, 40, 0, false, DAILY_CAP_REACHED, 10, 300, false, 40",
            "10, 0, 40, -1, true, '', 11, 300, false, 1"})
    void consumeAiQuestionAppliesTheMonthlyAndDailyLimits(int used, int bonus, int dayQuestions, int dayOffset,
                                                          boolean allowed, String reason, int usedAfter, int limit,
                                                          boolean warn, int dayQuestionsAfter) {
        seedUsage(other.tenantId(), used, bonus, dayQuestions, today().plusDays(dayOffset));

        ConsumeAiQuestionResponse response = consume(other.tenantId(), otherOwnerUserId);

        assertEquals(allowed, response.getAllowed());
        assertEquals(reason, response.getReason());
        assertEquals(usedAfter, response.getUsed());
        assertEquals(limit, response.getLimit());
        assertEquals(warn, response.getWarn());
        Map<String, Object> row = jdbc.queryForMap("SELECT questions_used, day_questions, day_date FROM ai_usage_periods "
                + "WHERE tenant_id = ?", other.tenantId());
        assertEquals(usedAfter, ((Number) row.get("questions_used")).intValue());
        assertEquals(dayQuestionsAfter, ((Number) row.get("day_questions")).intValue());
        if (allowed) {
            assertEquals(today(), ((java.sql.Date) row.get("day_date")).toLocalDate(), "an allowed question stamps today");
        }
    }

    @Test
    void theFirstQuestionOfAMonthCreatesTheUsageRow() {
        jdbc.update("DELETE FROM ai_usage_periods WHERE tenant_id = ?", other.tenantId());

        ConsumeAiQuestionResponse response = consume(other.tenantId(), otherOwnerUserId);

        assertTrue(response.getAllowed());
        assertEquals(1, response.getUsed());
        assertEquals(1, api.count("SELECT count(*) FROM ai_usage_periods WHERE tenant_id = ? AND questions_used = 1 AND day_questions = 1 "
                + "AND period_month = ?", other.tenantId(), java.sql.Date.valueOf(today().withDayOfMonth(1))));
    }

    @ParameterizedTest(name = "used={0} dayQuestions={1} -> {2}")
    @CsvSource({"299, 0, MONTHLY_QUOTA_EXHAUSTED", "10, 39, DAILY_CAP_REACHED"})
    void twoConcurrentQuestionsOnTheLastSlotAdmitExactlyOne(int used, int dayQuestions, String refusedReason) throws Exception {
        seedUsage(other.tenantId(), used, 0, dayQuestions, today());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ConsumeAiQuestionResponse>> results = List.of(
                    pool.submit(() -> { start.await(); return consume(other.tenantId(), otherOwnerUserId); }),
                    pool.submit(() -> { start.await(); return consume(other.tenantId(), otherOwnerUserId); }));
            start.countDown();
            List<ConsumeAiQuestionResponse> responses = List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS));

            assertEquals(1, responses.stream().filter(ConsumeAiQuestionResponse::getAllowed).count());
            assertEquals(List.of(refusedReason), responses.stream().filter(r -> !r.getAllowed()).map(ConsumeAiQuestionResponse::getReason).toList());
            assertEquals(used + 1, api.count("SELECT questions_used FROM ai_usage_periods WHERE tenant_id = ?", other.tenantId()));
            assertEquals(dayQuestions + 1, api.count("SELECT day_questions FROM ai_usage_periods WHERE tenant_id = ?", other.tenantId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recordedTokensAccumulateOnTheMonthRowWithoutCountingAQuestion() {
        seedUsage(other.tenantId(), 7, 0, 3, today());

        stub().recordAiUsage(RecordAiUsageRequest.newBuilder().setTenantId(other.tenantId().toString())
                .setInputTokens(100).setOutputTokens(50).build());
        stub().recordAiUsage(RecordAiUsageRequest.newBuilder().setTenantId(other.tenantId().toString())
                .setInputTokens(20).setOutputTokens(10).build());

        Map<String, Object> row = jdbc.queryForMap("SELECT questions_used, input_tokens, output_tokens FROM ai_usage_periods "
                + "WHERE tenant_id = ?", other.tenantId());
        assertEquals(7, ((Number) row.get("questions_used")).intValue());
        assertEquals(120L, ((Number) row.get("input_tokens")).longValue());
        assertEquals(60L, ((Number) row.get("output_tokens")).longValue());
    }

    // ------------------------------------------------------------------ read-only

    @Test
    void theInsightRpcsWriteNothing() {
        String[] tables = {"audit_logs", "daily_summary_dispatches", "inventory_movements", "sales_orders", "payments",
                "cash_shifts", "cash_expenses"};
        int[] before = new int[tables.length];
        for (int i = 0; i < tables.length; i++) {
            before[i] = api.count("SELECT count(*) FROM " + tables[i]);
        }
        stub().getSalesSummary(salesRequest(owner.tenantId(), ownerUserId, today().minusDays(1).toString(), today().toString()));
        stub().getRestockSuggestions(restockRequest(owner.tenantId(), ownerUserId));
        stub().getAnomalies(anomalyRequest(owner.tenantId(), ownerUserId, today().toString()));
        stub().getLeakageReport(leakageRequest(owner.tenantId(), ownerUserId, "", ""));
        stub().getMenuMargins(marginRequest(owner.tenantId(), ownerUserId, "100"));
        for (int i = 0; i < tables.length; i++) {
            assertEquals(before[i], api.count("SELECT count(*) FROM " + tables[i]), tables[i]);
        }
    }

    // ------------------------------------------------------------------ daily summary dispatch row

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
        assertTrue(api.count("SELECT count(*) FROM membership_outlet_access a JOIN tenant_memberships m ON m.id = a.membership_id "
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

    private ConsumeAiQuestionResponse consume(UUID tenantId, UUID userId) {
        return stub().consumeAiQuestion(ConsumeAiQuestionRequest.newBuilder().setTenantId(tenantId.toString())
                .setUserId(userId.toString()).build());
    }

    /** Replaces the tenant's usage row of the current outlet month with the given counters. */
    private void seedUsage(UUID tenantId, int used, int bonus, int dayQuestions, LocalDate dayDate) {
        jdbc.update("DELETE FROM ai_usage_periods WHERE tenant_id = ?", tenantId);
        jdbc.update("INSERT INTO ai_usage_periods (tenant_id, period_month, questions_used, bonus_questions, day_date, "
                + "day_questions, input_tokens, output_tokens, updated_at) VALUES (?, ?, ?, ?, ?, ?, 0, 0, now())",
                tenantId, java.sql.Date.valueOf(today().withDayOfMonth(1)), used, bonus, java.sql.Date.valueOf(dayDate),
                dayQuestions);
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

    /** Empty from/to use the default range; an empty max is "below target only". */
    private static GetLeakageReportRequest leakageRequest(UUID tenantId, UUID userId, String from, String to) {
        return GetLeakageReportRequest.newBuilder().setTenantId(tenantId.toString()).setUserId(userId.toString())
                .setFrom(from).setTo(to).build();
    }

    private static GetMenuMarginsRequest marginRequest(UUID tenantId, UUID userId, String maxMarginPct) {
        return GetMenuMarginsRequest.newBuilder().setTenantId(tenantId.toString()).setUserId(userId.toString())
                .setMaxMarginPct(maxMarginPct).build();
    }

    private static StatusRuntimeException assertCode(Status.Code expected, Executable call) {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call);
        assertEquals(expected, error.getStatus().getCode(), "unexpected status: " + error.getStatus());
        return error;
    }

    // ------------------------------------------------------------------ value helpers

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
