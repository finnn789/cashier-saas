package org.lite.project.cashiersaas.integration.grpc;

import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records the port the gRPC server actually bound to. The test profile uses port 0 (OS picks a free port), and
 * the net.devh starter does not expose the bound port through its factory, only through this event.
 */
class GrpcServerPortHolder implements ApplicationListener<GrpcServerStartedEvent> {

    private final AtomicInteger port = new AtomicInteger(-1);

    @Override
    public void onApplicationEvent(GrpcServerStartedEvent event) {
        port.set(event.getServer().getPort());
    }

    int port() {
        int value = port.get();
        if (value <= 0) {
            throw new IllegalStateException("The gRPC server has not reported a bound port.");
        }
        return value;
    }
}
