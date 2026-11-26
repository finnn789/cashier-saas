package org.lite.project.cashiersaas.integration.fnb;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lite.project.cashiersaas.integration.support.ApiTestSupport.assertStatus;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Image upload to local disk (TASK-113): upload, public serving, tenant boundary and the 2 MB limit. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaUploadIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String AUTH = HttpHeaders.AUTHORIZATION;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final Path MEDIA_DIR = tempDir();

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;
    @Value("${local.server.port}")
    private int port;

    private ApiTestSupport api;

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("cashier-media-it");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        registry.add("app.media.dir", MEDIA_DIR::toString);
        registry.add("app.media.public-base-url", () -> "http://localhost:8080");
    }

    @BeforeAll
    void setUpAll() {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    private static String uploadUrl(String area, MerchantFixture merchant) {
        return "/api/v1/" + area + "/outlets/" + merchant.outletId() + "/media/images";
    }

    private MvcResult upload(String area, MerchantFixture pathOutlet, String bearer, String name, byte[] bytes)
            throws Exception {
        var request = multipart(uploadUrl(area, pathOutlet)).file(new MockMultipartFile("file", name, "image/png", bytes));
        return api.call(bearer == null ? request : request.header(AUTH, bearer));
    }

    private static long filesUnder(Path folder) throws IOException {
        if (!Files.exists(folder)) return 0;
        try (Stream<Path> files = Files.list(folder)) {
            return files.count();
        }
    }

    @Test
    void ownerUploadsPngAndAnyoneCanFetchItWithLongCache() throws Exception {
        MerchantFixture owner = api.onboardOwner("media-owner");

        MvcResult created = upload("merchant", owner, owner.bearer(), "photo.png", PNG);

        assertStatus(201, created);
        String path = URI.create(ApiTestSupport.read(created, "$.data.url")).getPath();
        assertTrue(path.startsWith("/media/" + owner.tenantId() + "/") && path.endsWith(".png"), path);
        MvcResult served = api.call(get(path));
        assertStatus(200, served);
        assertArrayEquals(PNG, served.getResponse().getContentAsByteArray());
        assertEquals("image/png", served.getResponse().getContentType());
        assertTrue(served.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("max-age=31536000"));
    }

    @Test
    void cashierMayUploadThroughTheCashierPath() throws Exception {
        MerchantFixture owner = api.onboardOwner("media-cashier-owner");
        MerchantFixture cashier = api.onboardCashier(owner, "media-cashier");

        assertStatus(201, upload("cashier", cashier, cashier.bearer(), "photo.png", PNG));
    }

    @Test
    void uploadToAnotherTenantsOutletIsDeniedAndWritesNothing() throws Exception {
        MerchantFixture tenantA = api.onboardOwner("media-a");
        MerchantFixture tenantB = api.onboardOwner("media-b");

        int status = upload("merchant", tenantB, tenantA.bearer(), "photo.png", PNG).getResponse().getStatus();

        assertTrue(status == 403 || status == 404, "status " + status);
        assertEquals(0, filesUnder(MEDIA_DIR.resolve(tenantA.tenantId().toString())));
        assertEquals(0, filesUnder(MEDIA_DIR.resolve(tenantB.tenantId().toString())));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        MerchantFixture owner = api.onboardOwner("media-invalid");

        assertStatus(400, upload("merchant", owner, owner.bearer(), "a.png", "plain text".getBytes(StandardCharsets.UTF_8)));
        assertStatus(400, api.call(multipart(uploadUrl("merchant", owner)).header(AUTH, owner.bearer())));
        assertStatus(401, upload("merchant", owner, null, "photo.png", PNG));
        assertEquals(0, filesUnder(MEDIA_DIR.resolve(owner.tenantId().toString())));
    }

    @Test
    void fileOverTwoMegabytesIsRejectedWith413() throws Exception {
        MerchantFixture owner = api.onboardOwner("media-big");
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        String boundary = "----media" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(big);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        // Real HTTP: the servlet container enforces the multipart limit, MockMvc does not.
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + uploadUrl("merchant", owner)))
                .header(AUTH, owner.bearer())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode(), response.body());
        assertEquals(0, filesUnder(MEDIA_DIR.resolve(owner.tenantId().toString())));
    }
}
