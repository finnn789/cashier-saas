package org.lite.project.cashiersaas.integration.identity;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Registration with a required, normalized staff phone number against real PostgreSQL and the real HTTP chain. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisterPhoneNumberIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String REGISTER_URL = "/api/v1/auth/register";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;

    private ApiTestSupport api;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpAll() {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        jdbc = api.jdbc();
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    @Test
    void registerStoresTheNormalizedPhoneAndNeverEchoesIt() throws Exception {
        String email = uniqueEmail();

        MvcResult result = api.send(register(email, "0812-3456-7890"), 201);

        assertEquals("6281234567890", storedPhone(email));
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("0812-3456-7890"), "raw phone must not be echoed");
        assertFalse(body.contains("081234567890"), "phone must not be echoed");
        assertFalse(body.contains("6281234567890"), "normalized phone must not be echoed");
        assertFalse(body.contains("phoneNumber"), "register response must not expose a phone field");
        assertNotNull(JsonPath.read(body, "$.data.accessToken"));
    }

    @Test
    void internationalAndLocalFormatsOfTheSameNumberNormalizeIdentically() throws Exception {
        String plusEmail = uniqueEmail();
        String localEmail = uniqueEmail();

        api.send(register(plusEmail, "+62 812-3456-7890"), 201);
        api.send(register(localEmail, "(0812) 3456 7890"), 201);

        assertEquals("6281234567890", storedPhone(plusEmail));
        assertEquals("6281234567890", storedPhone(localEmail));
    }

    @Test
    void invalidPhoneNumberReturnsBadRequestWithoutEchoingItAndCreatesNoUser() throws Exception {
        String email = uniqueEmail();
        String invalidPhone = "0812-345-678a";

        MvcResult result = api.call(register(email, invalidPhone));

        assertEquals(400, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertEquals("Nomor telepon tidak valid.", JsonPath.read(body, "$.message"));
        assertFalse(body.contains(invalidPhone), "rejected phone must not be echoed");
        assertEquals(0, countUsers(email));
    }

    @Test
    void tooShortPhoneNumberIsRejected() throws Exception {
        String email = uniqueEmail();

        MvcResult result = api.call(register(email, "0812"));

        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, countUsers(email));
    }

    @Test
    void missingOrBlankPhoneNumberIsRejectedByValidation() throws Exception {
        String missingEmail = uniqueEmail();
        MvcResult missing = api.call(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Tanpa Telepon\",\"email\":\"" + missingEmail
                        + "\",\"password\":\"Passw0rd!23\"}"));
        assertEquals(400, missing.getResponse().getStatus());
        assertPhoneNumberValidationError(missing);
        assertEquals(0, countUsers(missingEmail));

        String blankEmail = uniqueEmail();
        MvcResult blank = api.call(register(blankEmail, "   "));
        assertEquals(400, blank.getResponse().getStatus());
        assertPhoneNumberValidationError(blank);
        assertEquals(0, countUsers(blankEmail));
    }

    @Test
    void samePhoneNumberMayBelongToSeveralStaffAccounts() throws Exception {
        String first = uniqueEmail();
        String second = uniqueEmail();

        api.send(register(first, "081234567891"), 201);
        api.send(register(second, "081234567891"), 201);

        assertEquals("6281234567891", storedPhone(first));
        assertEquals("6281234567891", storedPhone(second));
    }

    @Test
    void migrationAddsANullableVarchar20PhoneColumn() {
        Map<String, Object> column = jdbc.queryForMap("SELECT is_nullable, character_maximum_length, data_type "
                + "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'staff_users' "
                + "AND column_name = 'phone_number'");

        assertEquals("YES", column.get("is_nullable"));
        assertEquals(20, ((Number) column.get("character_maximum_length")).intValue());
        assertEquals("character varying", column.get("data_type"));
    }

    @Test
    void legacyStaffRowsWithoutAPhoneRemainValid() {
        UUID id = UUID.randomUUID();
        String email = uniqueEmail();

        jdbc.update("INSERT INTO staff_users (id, email, password_hash, full_name, status, created_at, updated_at, "
                + "version) VALUES (?, ?, 'hash', 'Legacy', 'ACTIVE', now(), now(), 0)", id, email);

        assertNull(storedPhone(email));
    }

    private void assertPhoneNumberValidationError(MvcResult result) throws Exception {
        List<String> fields = JsonPath.read(result.getResponse().getContentAsString(),
                "$.validationErrors[*].field");
        assertEquals(List.of("phoneNumber"), fields);
    }

    private MockHttpServletRequestBuilder register(String email, String phone) {
        return post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Staf Uji\",\"email\":\"" + email + "\",\"phoneNumber\":\"" + phone
                        + "\",\"password\":\"Passw0rd!23\"}");
    }

    private String storedPhone(String email) {
        List<String> phones = jdbc.query("SELECT phone_number FROM staff_users WHERE email = ?",
                (resultSet, rowNumber) -> resultSet.getString(1), email);
        assertEquals(1, phones.size(), "exactly one staff user expected for the email");
        return phones.get(0);
    }

    private int countUsers(String email) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM staff_users WHERE email = ?", Integer.class, email);
        return count == null ? 0 : count;
    }

    private static String uniqueEmail() {
        return "phone-" + UUID.randomUUID() + "@example.com";
    }
}
