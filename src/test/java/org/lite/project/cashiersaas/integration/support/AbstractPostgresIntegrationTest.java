package org.lite.project.cashiersaas.integration.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests. A single PostgreSQL container is shared by every Spring context in the JVM.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    /** Test-only gRPC service key (at least 16 characters). Never use outside tests. */
    public static final String GRPC_TEST_SERVICE_KEY = "test-only-grpc-service-key-0123456789";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("cashier_saas_test")
            .withUsername("cashier")
            .withPassword("cashier");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * gRPC server settings: the service key has no default in the main configuration, so every context needs one.
     * Port 0 lets the OS pick a free port per context (no clash with 9090 or between cached contexts); tests read
     * the real port from the GrpcServerStartedEvent.
     */
    @DynamicPropertySource
    static void grpc(DynamicPropertyRegistry registry) {
        registry.add("grpc.server.port", () -> "0");
        registry.add("app.grpc.service-key", () -> GRPC_TEST_SERVICE_KEY);
        // Generous limit so the rate limiter never interferes with functional tests.
        registry.add("app.grpc.requests-per-second", () -> "1000");
    }
}
