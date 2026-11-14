package org.lite.project.cashiersaas.integration.fnb;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
 * Record-only payments (TASK-078): payment labels (CRUD, limits, roles), the V8 schema, the label rules on payment,
 * and the per-label sales report, against real PostgreSQL, the real Flyway schema (V1..V8) and the security chain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FnbPaymentLabelIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final String PRICE = "25000";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;

    private ApiTestSupport api;

    @BeforeAll
    void setUpAll() {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    // ------------------------------------------------------------------ helpers

    private static String merchantLabels(MerchantFixture merchant) {
        return "/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/payment-labels";
    }

    private static String cashierLabels(MerchantFixture merchant) {
        return "/api/v1/cashier/outlets/" + merchant.outletId() + "/fnb/payment-labels";
    }

    private static String labelJson(String name, Integer displayOrder, Boolean active) {
        return "{\"name\":\"" + name + "\""
                + (displayOrder == null ? "" : ",\"displayOrder\":" + displayOrder)
                + (active == null ? "" : ",\"active\":" + active) + "}";
    }

    private MvcResult createLabel(MerchantFixture owner, String name) throws Exception {
        return api.call(post(merchantLabels(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(labelJson(name, null, null)));
    }

    private MvcResult updateLabel(MerchantFixture owner, String labelId, String json) throws Exception {
        return api.call(put(merchantLabels(owner) + "/" + labelId).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static List<Object> list(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private List<Object> merchantNames(MerchantFixture owner) throws Exception {
        return list(api.send(get(merchantLabels(owner)).header(AUTH, owner.bearer()), 200), "$.data[*].name");
    }

    private String labelIdByName(MerchantFixture owner, String name) throws Exception {
        MvcResult labels = api.send(get(merchantLabels(owner)).header(AUTH, owner.bearer()), 200);
        return String.valueOf(list(labels, "$.data[?(@.name=='" + name + "')].id").get(0));
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private int count(String sql, Object... args) {
        Integer value = api.jdbc().queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private String productFor(MerchantFixture owner) throws Exception {
        return api.createProduct(owner, api.createCategory(owner, "Minuman"), "Es Teh", PRICE);
    }

    // ------------------------------------------------------------------ schema and onboarding

    @Test
    void migrationV8IsAppliedMockGatewayIsRejectedAndOnboardingSeedsThreeDefaultLabels() throws Exception {
        assertEquals(1, count("SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success"));
        assertEquals(1, count("SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_payment_labels_outlet_name'"));
        assertEquals(3, count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('payment_labels', 'cash_expenses', 'receipt_sequences')"));
        String check = api.jdbc().queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'payments_method_check'", String.class);
        assertTrue(check.contains("MANUAL_NON_CASH") && check.contains("CASH"), check);
        assertFalse(check.contains("MOCK_GATEWAY"), check);

        MerchantFixture owner = api.onboardOwner("lbl-defaults");
        MvcResult labels = api.send(get(merchantLabels(owner)).header(AUTH, owner.bearer()), 200);
        assertEquals(List.of("QRIS", "Transfer", "Debit"), list(labels, "$.data[*].name"));
        assertEquals(List.of(1, 2, 3), list(labels, "$.data[*].displayOrder"));
        assertEquals(List.of(true, true, true), list(labels, "$.data[*].active"));
        assertEquals(3, count("SELECT count(*) FROM payment_labels WHERE outlet_id = ?", owner.outletId()));
        // Onboarding itself writes no audit rows for the defaults.
        assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'PAYMENT_LABEL_CHANGED'",
                owner.tenantId()));

        // The CHECK constraint (not just the enum) refuses the removed gateway method.
        api.openShift(owner, "0.00");
        String orderId = api.createPosOrder(owner, productFor(owner), 1);
        api.payCashExact(owner, orderId);
        DataIntegrityViolationException refused = assertThrows(DataIntegrityViolationException.class,
                () -> api.jdbc().update("UPDATE payments SET method = 'MOCK_GATEWAY' WHERE sales_order_id = ?::uuid", orderId));
        assertTrue(String.valueOf(refused.getMessage()).contains("payments_method_check"), refused.getMessage());
        assertEquals("CASH", api.jdbc().queryForObject("SELECT method FROM payments WHERE sales_order_id = ?::uuid",
                String.class, orderId));
    }

    // ------------------------------------------------------------------ CRUD and roles

    @Test
    void ownerManagesLabelsCashierReadsActiveOnlyAndOtherRolesAndTenantsAreRefused() throws Exception {
        MerchantFixture owner = api.onboardOwner("lbl-crud");
        MerchantFixture cashier = api.onboardCashier(owner, "lbl-crud-cashier");
        MerchantFixture other = api.onboardOwner("lbl-other");

        MvcResult created = createLabel(owner, "  GoPay  ");
        assertStatus(201, created);
        String goPayId = ApiTestSupport.read(created, "$.data.id");
        assertEquals("GoPay", ApiTestSupport.read(created, "$.data.name"));
        assertEquals("true", ApiTestSupport.read(created, "$.data.active"));
        assertEquals("4", ApiTestSupport.read(created, "$.data.displayOrder"), "default order is count + 1");

        String qrisId = labelIdByName(owner, "QRIS");
        MvcResult renamed = updateLabel(owner, qrisId, labelJson("QRIS Dinamis", 9, null));
        assertStatus(200, renamed);
        assertEquals("QRIS Dinamis", ApiTestSupport.read(renamed, "$.data.name"));
        assertEquals("9", ApiTestSupport.read(renamed, "$.data.displayOrder"));
        assertEquals("true", ApiTestSupport.read(renamed, "$.data.active"), "omitted active keeps the current value");

        String debitId = labelIdByName(owner, "Debit");
        MvcResult deactivated = updateLabel(owner, debitId, labelJson("Debit", null, false));
        assertStatus(200, deactivated);
        assertEquals("false", ApiTestSupport.read(deactivated, "$.data.active"));
        assertEquals("3", ApiTestSupport.read(deactivated, "$.data.displayOrder"), "omitted order keeps the current value");

        // Owner sees all, ordered by display order; the cashier sees only the active ones.
        assertEquals(List.of("Transfer", "Debit", "GoPay", "QRIS Dinamis"), merchantNames(owner));
        MvcResult cashierView = api.send(get(cashierLabels(cashier)).header(AUTH, cashier.bearer()), 200);
        assertEquals(List.of("Transfer", "GoPay", "QRIS Dinamis"), list(cashierView, "$.data[*].name"));
        assertEquals(List.of(true, true, true), list(cashierView, "$.data[*].active"));
        MvcResult ownerOnCashierRoute = api.send(get(cashierLabels(owner)).header(AUTH, owner.bearer()), 200);
        assertEquals(3, list(ownerOnCashierRoute, "$.data[*].id").size(), "owner also gets active only on the cashier route");

        // A cashier cannot use the owner routes at all.
        assertStatus(403, api.call(get(merchantLabels(cashier)).header(AUTH, cashier.bearer())));
        assertStatus(403, api.call(post(merchantLabels(cashier)).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(labelJson("Hack", null, null))));
        assertStatus(403, api.call(put(merchantLabels(cashier) + "/" + goPayId).header(AUTH, cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(labelJson("Hack", null, null))));
        assertEquals(401, api.call(get(cashierLabels(owner))).getResponse().getStatus());

        // Another tenant sees no evidence: foreign outlet path and foreign label id are both 404.
        assertStatus(404, api.call(get(merchantLabels(owner)).header(AUTH, other.bearer())));
        assertStatus(404, api.call(get(cashierLabels(owner)).header(AUTH, other.bearer())));
        assertStatus(404, api.call(post(merchantLabels(owner)).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(labelJson("Hack", null, null))));
        assertStatus(404, api.call(put(merchantLabels(owner) + "/" + goPayId).header(AUTH, other.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(labelJson("Hack", null, null))));
        assertStatus(404, updateLabel(other, goPayId, labelJson("Hack", null, null)));
        assertStatus(404, updateLabel(owner, UUID.randomUUID().toString(), labelJson("Hack", null, null)));
        assertEquals(List.of("QRIS", "Transfer", "Debit"), merchantNames(other));

        // Validation.
        assertStatus(400, createLabel(owner, "   "));
        assertStatus(400, createLabel(owner, "x".repeat(41)));
        assertStatus(400, api.call(post(merchantLabels(owner)).header(AUTH, owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(labelJson("Valid", -1, null))));
        assertStatus(400, updateLabel(owner, goPayId, labelJson("", null, null)));
        assertEquals(4, count("SELECT count(*) FROM payment_labels WHERE outlet_id = ?", owner.outletId()));

        // Every successful write is audited with the label name and its resulting state; failures are not.
        assertEquals(3, count("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'PAYMENT_LABEL_CHANGED'",
                owner.tenantId()));
        assertEquals("name=GoPay active=true", api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'PAYMENT_LABEL_CHANGED' AND entity_id = ?::uuid", String.class, owner.tenantId(), goPayId));
        assertEquals("name=Debit active=false", api.jdbc().queryForObject("SELECT details FROM audit_logs WHERE tenant_id = ? "
                + "AND action = 'PAYMENT_LABEL_CHANGED' AND entity_id = ?::uuid", String.class, owner.tenantId(), debitId));
    }

    @Test
    void duplicateNamesAreRejectedCaseInsensitivelyOnCreateAndUpdateAndAnOutletHoldsAtMostTwentyLabels() throws Exception {
        MerchantFixture owner = api.onboardOwner("lbl-limits");
        String transferId = labelIdByName(owner, "Transfer");

        MvcResult duplicate = createLabel(owner, "qris");
        assertStatus(409, duplicate);
        assertTrue(duplicate.getResponse().getContentAsString().contains("Label sudah ada."));
        assertStatus(409, createLabel(owner, "TRANSFER"));
        assertStatus(409, createLabel(owner, "  qris  "));
        MvcResult duplicateOnUpdate = updateLabel(owner, transferId, labelJson("QrIs", null, null));
        assertStatus(409, duplicateOnUpdate);
        assertTrue(duplicateOnUpdate.getResponse().getContentAsString().contains("Label sudah ada."));
        // Renaming a label to a different casing of its own name is not a duplicate.
        MvcResult selfRename = updateLabel(owner, transferId, labelJson("TRANSFER", null, null));
        assertStatus(200, selfRename);
        assertEquals("TRANSFER", ApiTestSupport.read(selfRename, "$.data.name"));
        // The same name is free in another outlet/tenant.
        MerchantFixture other = api.onboardOwner("lbl-limits-other");
        assertStatus(201, createLabel(other, "GoPay"));
        assertStatus(201, createLabel(owner, "GoPay"));
        assertStatus(409, createLabel(owner, "gopay"));

        // 3 defaults + GoPay = 4; sixteen more reach the limit of 20.
        for (int i = 1; i <= 16; i++) {
            assertStatus(201, createLabel(owner, "Label " + i));
        }
        assertEquals(20, count("SELECT count(*) FROM payment_labels WHERE outlet_id = ?", owner.outletId()));
        MvcResult tooMany = createLabel(owner, "Label 17");
        assertStatus(409, tooMany);
        assertTrue(tooMany.getResponse().getContentAsString().contains("Jumlah label maksimal 20."));
        assertEquals(20, count("SELECT count(*) FROM payment_labels WHERE outlet_id = ?", owner.outletId()));
        // Deactivated labels still count; the limit is about rows, and editing stays possible.
        assertStatus(200, updateLabel(owner, labelIdByName(owner, "Label 1"), labelJson("Label 1", null, false)));
        assertStatus(409, createLabel(owner, "Label 18"));
        assertEquals(4, count("SELECT count(*) FROM payment_labels WHERE outlet_id = ?", other.outletId()), "3 defaults + GoPay");
    }

    // ------------------------------------------------------------------ label rules on payment

    @Test
    void nonCashPaymentNeedsAnActiveLabelOfTheOutletCashRefusesOneAndTheLabelNameIsSnapshotted() throws Exception {
        MerchantFixture owner = api.onboardOwner("lbl-pay");
        MerchantFixture other = api.onboardOwner("lbl-pay-other");
        String productId = productFor(owner);
        String orderId = api.createPosOrder(owner, productId, 1);
        String total = api.orderTotal(owner, orderId);
        api.openShift(owner, "0.00");
        String qrisId = labelIdByName(owner, "QRIS");
        String debitId = labelIdByName(owner, "Debit");
        assertStatus(200, updateLabel(owner, debitId, labelJson("Debit", null, false)));

        MvcResult noLabel = api.pay(owner, orderId, "MANUAL_NON_CASH", total, null, null);
        assertStatus(400, noLabel);
        assertTrue(noLabel.getResponse().getContentAsString().contains("Pilih label pembayaran non-tunai."));
        MvcResult cashWithLabel = api.pay(owner, orderId, "CASH", total, qrisId, null);
        assertStatus(400, cashWithLabel);
        assertTrue(cashWithLabel.getResponse().getContentAsString().contains("Pembayaran tunai tidak memakai label."));
        assertStatus(400, api.pay(owner, orderId, "MANUAL_NON_CASH", total, UUID.randomUUID().toString(), null));
        assertStatus(400, api.pay(owner, orderId, "MANUAL_NON_CASH", total, api.firstActiveLabelId(other), null));
        MvcResult inactive = api.pay(owner, orderId, "MANUAL_NON_CASH", total, debitId, null);
        assertStatus(400, inactive);
        assertTrue(inactive.getResponse().getContentAsString().contains("Pilih label pembayaran non-tunai."));
        assertStatus(400, api.pay(owner, orderId, "MOCK_GATEWAY", total, null, null));
        assertEquals(0, count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId));
        assertEquals("UNPAID", ApiTestSupport.read(api.send(get("/api/v1/cashier/outlets/" + owner.outletId()
                + "/fnb/orders/" + orderId).header(AUTH, owner.bearer()), 200), "$.data.paymentStatus"));

        String key = "lbl-" + UUID.randomUUID();
        MvcResult paid = api.pay(owner, orderId, "MANUAL_NON_CASH", total, qrisId, key);
        assertTrue(paid.getResponse().getStatus() == 200 || paid.getResponse().getStatus() == 201,
                paid.getResponse().getContentAsString());
        assertEquals("MANUAL_NON_CASH", ApiTestSupport.read(paid, "$.data.method"));
        assertEquals("QRIS", ApiTestSupport.read(paid, "$.data.methodLabel"));
        assertEquals(0, new java.math.BigDecimal(ApiTestSupport.read(paid, "$.data.changeAmount")).signum());
        assertEquals("QRIS", api.jdbc().queryForObject("SELECT method_label FROM payments WHERE sales_order_id = ?::uuid",
                String.class, orderId));

        // Replay: same key, method, amount and label returns the stored payment; any difference is a conflict.
        MvcResult replay = api.pay(owner, orderId, "MANUAL_NON_CASH", total, qrisId, key);
        assertTrue(replay.getResponse().getStatus() == 200 || replay.getResponse().getStatus() == 201);
        assertEquals(ApiTestSupport.read(paid, "$.data.receiptNumber"), ApiTestSupport.read(replay, "$.data.receiptNumber"));
        assertStatus(409, api.pay(owner, orderId, "MANUAL_NON_CASH", total, labelIdByName(owner, "Transfer"), key));
        assertStatus(409, api.pay(owner, orderId, "CASH", total, null, key));
        assertEquals(1, count("SELECT count(*) FROM payments WHERE sales_order_id = ?::uuid", orderId));

        // The label name is a snapshot: renaming or deactivating the label later changes nothing on the payment,
        // and a replay of the stored payment still works.
        assertStatus(200, updateLabel(owner, qrisId, labelJson("QRIS Baru", null, false)));
        assertEquals("QRIS", api.jdbc().queryForObject("SELECT method_label FROM payments WHERE sales_order_id = ?::uuid",
                String.class, orderId));
        MvcResult afterRename = api.pay(owner, orderId, "MANUAL_NON_CASH", total, qrisId, key);
        assertEquals("QRIS", ApiTestSupport.read(afterRename, "$.data.methodLabel"));
        MvcResult receipt = api.send(get("/api/v1/cashier/outlets/" + owner.outletId() + "/fnb/orders/" + orderId
                + "/receipt").header(AUTH, owner.bearer()), 200);
        assertEquals("QRIS", ApiTestSupport.read(receipt, "$.data.methodLabel"));
    }

    // ------------------------------------------------------------------ report per label

    @Test
    void salesReportAndCsvBreakDownNonCashPerLabel() throws Exception {
        MerchantFixture owner = api.onboardOwner("lbl-report");
        String productId = productFor(owner);
        String qris = labelIdByName(owner, "QRIS");
        String transfer = labelIdByName(owner, "Transfer");
        api.openShift(owner, "0.00");
        api.payCashExact(owner, api.createPosOrder(owner, productId, 1));
        api.payNonCash(owner, api.createPosOrder(owner, productId, 1), qris);
        api.payNonCash(owner, api.createPosOrder(owner, productId, 2), qris);
        api.payNonCash(owner, api.createPosOrder(owner, productId, 1), transfer);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        String url = "/api/v1/merchant/outlets/" + owner.outletId() + "/fnb/reports/sales";
        MvcResult report = api.send(get(url).param("startDate", today.minusDays(1).toString())
                .param("endDate", today.plusDays(1).toString()).header(AUTH, owner.bearer()), 200);

        assertEquals("4", ApiTestSupport.read(report, "$.data.summary.transactionCount"));
        assertEquals("3", ApiTestSupport.read(report, "$.data.paymentBreakdown.length()"));
        assertEquals(List.of(1), list(report, "$.data.paymentBreakdown[?(@.method=='CASH')].transactionCount"));
        assertEquals(0, list(report, "$.data.paymentBreakdown[?(@.method=='CASH')].methodLabel").size(),
                "cash rows carry no label");
        assertEquals(List.of(2), list(report, "$.data.paymentBreakdown[?(@.methodLabel=='QRIS')].transactionCount"));
        assertEquals(List.of(1), list(report, "$.data.paymentBreakdown[?(@.methodLabel=='Transfer')].transactionCount"));
        assertEquals(List.of("MANUAL_NON_CASH"), list(report, "$.data.paymentBreakdown[?(@.methodLabel=='Transfer')].method"));
        assertEquals(2, list(report, "$.data.transactions[?(@.methodLabel=='QRIS')].orderId").size());

        MvcResult csv = api.send(get(url + "/csv").param("startDate", today.minusDays(1).toString())
                .param("endDate", today.plusDays(1).toString()).header(AUTH, owner.bearer()), 200);
        String content = ApiTestSupport.read(csv, "$.data.content");
        String[] lines = content.split("\r\n");
        assertTrue(lines[0].startsWith("Order Number,Receipt Number,Status,Payment Method,Payment Label,Paid At,"), lines[0]);
        assertEquals(5, lines.length, "header + 4 sales");
        assertEquals(2, java.util.Arrays.stream(lines).filter(line -> line.contains("\"MANUAL_NON_CASH\",\"QRIS\",")).count());
        assertEquals(1, java.util.Arrays.stream(lines).filter(line -> line.contains("\"MANUAL_NON_CASH\",\"Transfer\",")).count());
        assertEquals(1, java.util.Arrays.stream(lines).filter(line -> line.contains("\"CASH\",\"\",")).count());
    }
}
