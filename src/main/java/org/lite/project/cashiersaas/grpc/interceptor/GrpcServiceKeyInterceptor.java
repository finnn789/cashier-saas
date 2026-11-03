package org.lite.project.cashiersaas.grpc.interceptor;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.grpc.policy.GrpcRateLimiter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates internal callers with a shared service key and applies a global rate limit before
 * any RPC handler runs. The key is never logged or echoed back to the caller.
 */
@GrpcGlobalServerInterceptor
public class GrpcServiceKeyInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> SERVICE_KEY_HEADER =
            Metadata.Key.of("x-internal-service-key", Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expectedKey;
    private final GrpcRateLimiter rateLimiter;

    public GrpcServiceKeyInterceptor(GrpcSecurityProperties properties, GrpcRateLimiter rateLimiter) {
        this.expectedKey = properties.getServiceKey().getBytes(StandardCharsets.UTF_8);
        this.rateLimiter = rateLimiter;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String provided = headers.get(SERVICE_KEY_HEADER);
        if (provided == null
                || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid or missing service credentials"),
                    new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        }
        if (!rateLimiter.tryAcquire()) {
            call.close(Status.RESOURCE_EXHAUSTED.withDescription("Rate limit exceeded"), new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        }
        return next.startCall(call, headers);
    }
}
