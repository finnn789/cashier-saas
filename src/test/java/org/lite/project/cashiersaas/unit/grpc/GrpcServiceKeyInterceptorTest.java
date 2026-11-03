package org.lite.project.cashiersaas.unit.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.grpc.interceptor.GrpcServiceKeyInterceptor;
import org.lite.project.cashiersaas.grpc.policy.GrpcRateLimiter;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class GrpcServiceKeyInterceptorTest {

    private static final String REAL_KEY = "unit-test-internal-service-key-0001";
    private static final String WRONG_KEY = "wrong-key-value-that-is-long-enough";

    private final AtomicLong clock = new AtomicLong(0L);

    private ServerCall<String, String> call;
    private ServerCallHandler<String, String> next;
    private ServerCall.Listener<String> downstreamListener;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        downstreamListener = mock(ServerCall.Listener.class);
        when(next.startCall(any(), any())).thenReturn(downstreamListener);
    }

    private GrpcServiceKeyInterceptor interceptor(int requestsPerSecond) {
        GrpcSecurityProperties properties = new GrpcSecurityProperties();
        properties.setServiceKey(REAL_KEY);
        return new GrpcServiceKeyInterceptor(properties, new GrpcRateLimiter(requestsPerSecond, clock::get));
    }

    private static Metadata headersWithKey(String value) {
        Metadata headers = new Metadata();
        headers.put(GrpcServiceKeyInterceptor.SERVICE_KEY_HEADER, value);
        return headers;
    }

    private Status verifyRejected(ServerCall.Listener<String> listener, Status.Code expectedCode) {
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        verify(next, never()).startCall(any(), any());
        assertNotNull(listener, "a no-op listener must be returned");
        assertNotSame(downstreamListener, listener);
        assertEquals(expectedCode, status.getValue().getCode());
        String description = status.getValue().getDescription();
        assertNotNull(description);
        assertFalse(description.contains(REAL_KEY), "description must never echo the real key");
        return status.getValue();
    }

    @Test
    void headerNameIsTheInternalServiceKey() {
        assertEquals("x-internal-service-key", GrpcServiceKeyInterceptor.SERVICE_KEY_HEADER.originalName());
    }

    @Test
    void validKeyDelegatesToNextHandler() {
        Metadata headers = headersWithKey(REAL_KEY);

        ServerCall.Listener<String> listener = interceptor(5).interceptCall(call, headers, next);

        assertSame(downstreamListener, listener);
        verify(next).startCall(call, headers);
        verify(call, never()).close(any(Status.class), any(Metadata.class));
    }

    @Test
    void missingHeaderIsUnauthenticated() {
        ServerCall.Listener<String> listener = interceptor(5).interceptCall(call, new Metadata(), next);

        Status status = verifyRejected(listener, Status.Code.UNAUTHENTICATED);
        assertEquals("Invalid or missing service credentials", status.getDescription());
    }

    @Test
    void wrongKeyIsUnauthenticatedAndNotEchoed() {
        ServerCall.Listener<String> listener =
                interceptor(5).interceptCall(call, headersWithKey(WRONG_KEY), next);

        Status status = verifyRejected(listener, Status.Code.UNAUTHENTICATED);
        assertFalse(status.getDescription().contains(WRONG_KEY));
    }

    @Test
    void keyThatIsOnlyAPrefixOfTheRealKeyIsRejected() {
        String prefix = REAL_KEY.substring(0, REAL_KEY.length() - 1);

        ServerCall.Listener<String> listener =
                interceptor(5).interceptCall(call, headersWithKey(prefix), next);

        verifyRejected(listener, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void keyWithExtraTrailingCharacterIsRejected() {
        ServerCall.Listener<String> listener =
                interceptor(5).interceptCall(call, headersWithKey(REAL_KEY + "x"), next);

        verifyRejected(listener, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void keyDifferingInCaseIsRejected() {
        ServerCall.Listener<String> listener =
                interceptor(5).interceptCall(call, headersWithKey(REAL_KEY.toUpperCase()), next);

        verifyRejected(listener, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void emptyHeaderValueIsRejected() {
        ServerCall.Listener<String> listener =
                interceptor(5).interceptCall(call, headersWithKey(""), next);

        verifyRejected(listener, Status.Code.UNAUTHENTICATED);
    }

    @Test
    void rejectedListenerIgnoresCallbacksWithoutTouchingTheHandler() {
        ServerCall.Listener<String> listener = interceptor(5).interceptCall(call, new Metadata(), next);

        listener.onMessage("payload");
        listener.onHalfClose();
        listener.onCancel();
        listener.onComplete();

        verifyNoInteractions(downstreamListener);
    }

    @Test
    void validKeyIsRateLimitedAfterCapacityIsExhausted() {
        GrpcServiceKeyInterceptor interceptor = interceptor(1);

        ServerCall.Listener<String> first = interceptor.interceptCall(call, headersWithKey(REAL_KEY), next);
        ServerCall.Listener<String> second = interceptor.interceptCall(call, headersWithKey(REAL_KEY), next);

        assertSame(downstreamListener, first);
        assertNotSame(downstreamListener, second);
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, status.getValue().getCode());
        assertFalse(status.getValue().getDescription().contains(REAL_KEY));
        verify(next).startCall(any(), any());
    }

    @Test
    void rateLimitRecoversAfterTimePasses() {
        GrpcServiceKeyInterceptor interceptor = interceptor(1);
        interceptor.interceptCall(call, headersWithKey(REAL_KEY), next);
        interceptor.interceptCall(call, headersWithKey(REAL_KEY), next);

        clock.addAndGet(1_500_000_000L);
        ServerCall.Listener<String> afterRefill = interceptor.interceptCall(call, headersWithKey(REAL_KEY), next);

        assertSame(downstreamListener, afterRefill);
    }

    @Test
    void unauthenticatedCallsDoNotConsumeRateLimitTokens() {
        GrpcServiceKeyInterceptor interceptor = interceptor(1);

        interceptor.interceptCall(call, headersWithKey(WRONG_KEY), next);
        interceptor.interceptCall(call, new Metadata(), next);
        ServerCall.Listener<String> valid = interceptor.interceptCall(call, headersWithKey(REAL_KEY), next);

        assertSame(downstreamListener, valid);
        verify(next).startCall(any(), any());
    }
}
