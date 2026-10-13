package org.lite.project.cashiersaas.configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.lite.project.cashiersaas.component.FnbRealtimeHandshakeInterceptor;
import org.lite.project.cashiersaas.component.FnbRealtimeWebSocketHandler;
import org.lite.project.cashiersaas.service.FnbRealtimeTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class FnbWebSocketConfiguration implements WebSocketConfigurer {
    private static final String DEFAULT_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173";

    @Value("${app.fnb.realtime.allowed-origins:" + DEFAULT_ORIGINS + "}")
    private String allowedOrigins;
    private FnbRealtimeWebSocketHandler handler;
    private FnbRealtimeHandshakeInterceptor interceptor;

    @Autowired
    public void setHandler(FnbRealtimeWebSocketHandler handler) {
        this.handler = handler;
    }

    @Autowired
    public void setInterceptor(FnbRealtimeHandshakeInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, FnbRealtimeTicketService.PATH)
                .addInterceptors(interceptor)
                .setAllowedOrigins(allowedOrigins(allowedOrigins).toArray(String[]::new));
    }

    @Bean(name = "fnbRealtimeDeliveryExecutor", destroyMethod = "shutdown")
    public static ExecutorService fnbRealtimeDeliveryExecutor() {
        return new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1_000),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService fnbRealtimeMaintenanceScheduler(FnbRealtimeTicketService tickets,
                                                                      FnbRealtimeWebSocketHandler websocketHandler) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                tickets.cleanup();
                websocketHandler.maintain();
            } catch (RuntimeException ignored) {
                // Keep recurring maintenance alive after an unexpected adapter failure.
            }
        }, 5, 5, TimeUnit.SECONDS);
        return scheduler;
    }

    @Bean
    public ServletServerContainerFactoryBean fnbWebSocketContainerFactory() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setAsyncSendTimeout(5_000L);
        factory.setMaxSessionIdleTimeout(60_000L);
        factory.setMaxTextMessageBufferSize(16 * 1024);
        return factory;
    }

    public static List<String> allowedOrigins(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("app.fnb.realtime.allowed-origins must not be empty.");
        List<String> origins = Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (origins.isEmpty()) throw new IllegalStateException("app.fnb.realtime.allowed-origins must not be empty.");
        for (String origin : origins) validateOrigin(origin);
        return origins;
    }

    private static void validateOrigin(String origin) {
        URI uri = URI.create(origin);
        if (origin.contains("*") || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65_535 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalStateException("Invalid explicit realtime origin.");
        }
    }
}
