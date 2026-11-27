package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.dto.response.platform.PlatformPageResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantItemResponse;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.PlatformAdminRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.service.PaymentGateway;
import org.lite.project.cashiersaas.service.PlatformAccessGuard;
import org.lite.project.cashiersaas.service.PlatformDashboardService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlatformDashboardServiceTest {
    private final PlatformPrincipal principal = new PlatformPrincipal(UUID.randomUUID(), "admin@example.com", UUID.randomUUID());

    @Mock
    private PlatformAccessGuard guard;
    @Mock
    private PlatformAdminRepository admin;
    @Mock
    private PlatformBillingRepository billing;
    @Mock
    private AiUsageRepository aiUsage;
    @Mock
    private PaymentGateway gateway;
    @InjectMocks
    private PlatformDashboardService service;

    @Test
    void everyReadStopsAtTheGuardForARevokedAdminBeforeTouchingData() {
        when(guard.require(principal)).thenThrow(new ResourceNotFoundException());

        assertAll(
                () -> assertThrows(ResourceNotFoundException.class, () -> service.overview(principal)),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.tenants(principal, null, null, null, 0, 10)),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.tenant(principal, UUID.randomUUID())),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.invoices(principal, null, null, 0, 10)),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.audit(principal, null, 0, 10)));
        verifyNoInteractions(admin, billing, aiUsage);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-5,1", "20,20", "50,50", "500,50"})
    void pageSizeIsClampedToOneThroughFiftyAndNegativePagesStartAtZero(int requestedSize, int expectedSize) {
        when(admin.tenantPage(any(), any(), any(), any(), any(), eq(0), eq(expectedSize))).thenReturn(List.of());

        PlatformPageResponse<PlatformTenantItemResponse> page = service.tenants(principal, null, null, null, -3, requestedSize);

        assertEquals(0, page.getPage());
        assertEquals(expectedSize, page.getSize());
    }
}
