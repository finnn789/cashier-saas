package org.lite.project.cashiersaas.integration.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Drives the real HTTP API (security filter chain included) and prepares reference data. */
public final class ApiTestSupport {
    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;

    public ApiTestSupport(MockMvc mockMvc, DataSource dataSource) {
        this.mockMvc = mockMvc;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Reference data that migrations do not seed. */
    public void ensureFnbBusinessType() {
        jdbc.update("INSERT INTO business_types (id, code, name, is_active, created_at) "
                + "VALUES (gen_random_uuid(), 'FNB', 'Food and Beverage', true, now()) ON CONFLICT (code) DO NOTHING");
    }

    /** Removes all tenant data created by a test class; keeps schema history and reference data. */
    public void truncateTenantData() {
        List<String> tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename NOT IN ('flyway_schema_history', 'business_types')", String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Register, OWNER intent, free tier FNB, business profile: all through the public API. */
    public MerchantFixture onboardOwner(String label) throws Exception {
        String email = label + "-" + UUID.randomUUID() + "@example.com";
        String accountToken = read(send(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Owner " + label + "\",\"email\":\"" + email
                        + "\",\"password\":\"Passw0rd!23\"}"), 201), "$.data.accessToken");
        String bearer = "Bearer " + accountToken;
        send(put("/api/v1/onboarding/intent").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"intent\":\"OWNER\"}"), 200);
        String tenantId = read(send(post("/api/v1/onboarding/freetier").header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", "tier-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"businessTypeCode\":\"FNB\"}"), 200, 201),
                "$.data.tenantId");
        MvcResult profile = send(post("/api/v1/onboarding/" + tenantId + "/business-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Warung " + label + "\",\"outletName\":\"Outlet " + label
                        + "\",\"address\":\"Jl. Test 1\",\"phoneNumber\":\"08123456789\","
                        + "\"timeZone\":\"Asia/Jakarta\"}"), 200, 201);
        return new MerchantFixture(UUID.fromString(tenantId),
                UUID.fromString(read(profile, "$.data.outletId")), read(profile, "$.data.accessToken"));
    }

    public String createCategory(MerchantFixture merchant, String name) throws Exception {
        return read(send(post("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/categories")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"displayOrder\":1}"), 200, 201), "$.data.id");
    }

    public String createProduct(MerchantFixture merchant, String categoryId, String name, String price)
            throws Exception {
        return read(send(post("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/products")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":\"" + categoryId + "\",\"name\":\"" + name + "\",\"description\":\"d\","
                        + "\"price\":" + price + ",\"available\":true,\"displayOrder\":1}"), 200, 201), "$.data.id");
    }

    /** Creates a table and returns {tableId, qrToken}. */
    public String[] createTable(MerchantFixture merchant, String name) throws Exception {
        MvcResult result = send(post("/api/v1/merchant/outlets/" + merchant.outletId() + "/fnb/tables")
                .header(HttpHeaders.AUTHORIZATION, merchant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"active\":true}"), 200, 201);
        return new String[] {read(result, "$.data.id"), read(result, "$.data.qrToken")};
    }

    /** Performs the request and fails with the response body unless the status is one of the expected ones. */
    public MvcResult send(MockHttpServletRequestBuilder request, int... expectedStatuses) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        for (int expected : expectedStatuses) {
            // Some endpoints report "created" only in the body code while the HTTP status stays 200.
            boolean success = (actual == 200 || actual == 201) && (expected == 200 || expected == 201);
            if (actual == expected || success) {
                return result;
            }
        }
        throw new AssertionError("Expected status " + Arrays.toString(expectedStatuses) + " but was " + actual
                + " for " + result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
                + ": " + result.getResponse().getContentAsString());
    }

    public MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    public static String read(MvcResult result, String jsonPath) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return String.valueOf(value);
    }
}
