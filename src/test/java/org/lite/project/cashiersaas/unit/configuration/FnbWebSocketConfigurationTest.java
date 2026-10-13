package org.lite.project.cashiersaas.unit.configuration;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.configuration.FnbWebSocketConfiguration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class FnbWebSocketConfigurationTest {
    @Test
    void acceptsExplicitHttpAndHttpsOriginsIncludingValidPortBoundaries() {
        assertEquals(List.of("http://localhost:5173", "https://cashier.example", "https://example.com:1", "https://example.com:65535"),
                FnbWebSocketConfiguration.allowedOrigins(" http://localhost:5173,https://cashier.example,https://example.com:1,https://example.com:65535 "));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ",", "*", "https://*.example.com", "http://user@example.com", "https://example.com/path",
            "https://example.com/", "https://example.com:0", "https://example.com:65536", "https://example.com?token=x",
            "https://example.com#fragment", "ws://example.com", "example.com"})
    void rejectsInvalidOrigins(String origin) {
        assertThrows(IllegalStateException.class, () -> FnbWebSocketConfiguration.allowedOrigins(origin));
    }

    @Test
    void malformedUriSyntaxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FnbWebSocketConfiguration.allowedOrigins("http://"));
    }

    @Test
    void deliveryExecutorIsBoundedAndNeverRunsOverflowOnCallingThread() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) FnbWebSocketConfiguration.fnbRealtimeDeliveryExecutor();
        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(2, executor.getMaximumPoolSize());
            assertEquals(30, executor.getKeepAliveTime(TimeUnit.SECONDS));
            assertEquals(1_000, executor.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, executor.getRejectedExecutionHandler());
            assertEquals(0, executor.getPoolSize(), "Inspecting bounds must not start background workers");
        } finally {
            executor.shutdownNow();
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    void websocketContainerHasFiniteSendIdleAndPayloadBoundsWithoutStartingContainer() {
        ServletServerContainerFactoryBean factory = new FnbWebSocketConfiguration().fnbWebSocketContainerFactory();
        assertEquals(Long.valueOf(5_000), factory.getAsyncSendTimeout());
        assertEquals(Long.valueOf(60_000), factory.getMaxSessionIdleTimeout());
        assertEquals(Integer.valueOf(16 * 1024), factory.getMaxTextMessageBufferSize());
    }
}
