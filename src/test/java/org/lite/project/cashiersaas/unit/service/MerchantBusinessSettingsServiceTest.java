package org.lite.project.cashiersaas.unit.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.MerchantBusinessSettingsRequest;
import org.lite.project.cashiersaas.dto.response.MerchantBusinessSettingsResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.MerchantBusinessSettingsRepository;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.MerchantBusinessSettingsService;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MerchantBusinessSettingsServiceTest {
    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private MerchantBusinessSettingsRepository settings;
    @Mock private AuditLogRepository auditLogs;
    @InjectMocks private MerchantBusinessSettingsService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal owner = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.OWNERMERCHANT));

    @Test
    void getRequiresScopedOwnerAndMapsCurrentSettings() {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());

        MerchantBusinessSettingsResponse response = service.get(owner, outletId);

        assertEquals(outletId, response.getOutletId());
        assertEquals("Kopi Lama", response.getBusinessName());
        assertEquals("Outlet Lama", response.getOutletName());
        verify(guard).requireOwner(owner, outletId);
        verifyNoMoreInteractions(guard, orders, settings, auditLogs);
    }

    @Test
    void ownerReadDenialPropagatesWithoutRepositoryAccess() {
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());
        assertThrows(ResourceNotFoundException.class, () -> service.get(owner, outletId));
        verify(guard).requireOwner(owner, outletId);
        verifyNoInteractions(orders, settings, auditLogs);
    }

    @Test
    void ownerGuardFailureDoesNotMutateSettingsOrWriteAudit() {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class, () -> service.update(owner, outletId, validRequest()));

        assertEquals("Kopi Lama", fixture.tenant().getName());
        assertEquals("Outlet Lama", fixture.outlet().getName());
        verify(orders).lockTenant(tenantId);
        verify(guard).requireOwner(owner, outletId);
        verifyNoInteractions(settings, auditLogs);
    }

    @Test
    void normalizedUpdateChangesTenantAndOutletAndAuditsActorBeforeAndAfter() throws Exception {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        request.setBusinessName("  Kopi Baru  ");
        request.setAddress("  Jalan Baru  ");
        request.setOutletName("  Outlet Baru  ");
        request.setOutletAddress("  Lantai Dua  ");
        request.setTaxRate(new BigDecimal("11"));
        request.setLogoUrl("  https://example.test:443/logo.png  ");
        request.setPhoneNumber("  08999  ");
        request.setOutletPhoneNumber("  08888  ");
        request.setTimeZone("  Asia/Makassar  ");

        MerchantBusinessSettingsResponse response = service.update(owner, outletId, request);

        assertEquals("Kopi Baru", fixture.tenant().getName());
        assertEquals("Jalan Baru", fixture.tenant().getAddress());
        assertEquals(new BigDecimal("11.00"), fixture.tenant().getTaxRate());
        assertEquals("Outlet Baru", fixture.outlet().getName());
        assertEquals("Lantai Dua", fixture.outlet().getAddress());
        assertEquals("https://example.test:443/logo.png", fixture.tenant().getLogoUrl());
        assertEquals("08999", fixture.tenant().getPhoneNumber());
        assertEquals("08888", fixture.outlet().getPhoneNumber());
        assertEquals("Asia/Makassar", fixture.tenant().getTimeZone());
        assertEquals("Asia/Makassar", fixture.outlet().getTimeZone());
        assertEquals("Kopi Baru", response.getBusinessName());
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogs).persist(audit.capture());
        assertEquals(fixture.tenant(), audit.getValue().getTenant());
        assertEquals(fixture.outlet(), audit.getValue().getOutlet());
        assertEquals(fixture.actor(), audit.getValue().getActor());
        assertEquals(outletId, audit.getValue().getEntityId());
        assertEquals(AuditLogEntity.Action.BUSINESS_SETTINGS_CHANGED.name(), audit.getValue().getAction());
        assertEquals("BUSINESS_SETTINGS", audit.getValue().getEntityType());
        JsonNode details = new ObjectMapper().readTree(audit.getValue().getDetails());
        assertEquals("Kopi Lama", details.path("before").path("businessName").asText());
        assertEquals("Kopi Baru", details.path("after").path("businessName").asText());
        verify(orders).lockTenant(tenantId);
        verify(guard).requireOwner(owner, outletId);
        verify(settings, never()).hasCurrencyUse(any());
        verifyNoMoreInteractions(orders, guard, settings, auditLogs);
    }

    @Test
    void currencyChangeAfterHistoryFailsWithoutMutationOrAudit() {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        when(settings.hasCurrencyUse(tenantId)).thenReturn(true);
        MerchantBusinessSettingsRequest request = validRequest();
        request.setCurrency("USD");
        request.setBusinessName("Rejected business change");
        request.setOutletName("Rejected outlet change");
        request.setTaxRate(new BigDecimal("20.00"));

        assertThrows(OperationConflictException.class, () -> service.update(owner, outletId, request));

        assertEquals("IDR", fixture.tenant().getCurrency());
        assertEquals("Kopi Lama", fixture.tenant().getName());
        assertEquals(new BigDecimal("10.00"), fixture.tenant().getTaxRate());
        assertEquals("Outlet Lama", fixture.outlet().getName());
        verify(settings).hasCurrencyUse(tenantId);
        verifyNoInteractions(auditLogs);
    }

    @Test
    void currencyChangeWithoutHistorySucceedsAndAuditsBothCurrencies() throws Exception {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        when(settings.hasCurrencyUse(tenantId)).thenReturn(false);
        MerchantBusinessSettingsRequest request = identicalRequest(fixture);
        request.setCurrency("USD");

        MerchantBusinessSettingsResponse response = service.update(owner, outletId, request);

        assertEquals("USD", response.getCurrency());
        assertEquals("USD", fixture.tenant().getCurrency());
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogs).persist(audit.capture());
        JsonNode details = new ObjectMapper().readTree(audit.getValue().getDetails());
        assertEquals("IDR", details.path("before").path("currency").asText());
        assertEquals("USD", details.path("after").path("currency").asText());
        assertEquals(AuditLogEntity.Action.BUSINESS_SETTINGS_CHANGED.name(), audit.getValue().getAction());
        verify(settings).hasCurrencyUse(tenantId);
    }

    @Test
    void sameCurrencyDoesNotQueryHistoryAndScaleOnlyRatesDoNotAudit() {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = identicalRequest(fixture);
        request.setTaxRate(new BigDecimal("10.0"));
        request.setServiceChargeRate(new BigDecimal("5.0"));

        service.update(owner, outletId, request);

        assertEquals(new BigDecimal("10.00"), fixture.tenant().getTaxRate());
        assertEquals(new BigDecimal("5.00"), fixture.tenant().getServiceChargeRate());
        assertEquals("Jalan Lama", fixture.tenant().getAddress());
        assertEquals("08123", fixture.tenant().getPhoneNumber());
        assertEquals("Lantai Satu", fixture.outlet().getAddress());
        assertEquals("08124", fixture.outlet().getPhoneNumber());
        verify(settings, never()).hasCurrencyUse(any());
        verifyNoInteractions(auditLogs);
    }

    @ParameterizedTest(name = "rejects {0} without mutation or audit")
    @ValueSource(strings = {"currency-unknown", "currency-zero-decimals", "currency-three-decimals", "timezone",
            "tax-null", "tax-negative", "tax-over100", "tax-scale", "service-null", "service-negative",
            "service-over100", "service-scale", "logo-http", "logo-credentials", "logo-port0", "logo-port70000",
            "business-null", "business-blank", "business-long", "outlet-null", "outlet-blank", "outlet-long"})
    void rejectsInvalidValuesBeforeMutation(String scenario) {
        assertRejected(request -> {
            switch (scenario) {
                case "currency-unknown" -> request.setCurrency("ZZZ");
                case "currency-zero-decimals" -> request.setCurrency("JPY");
                case "currency-three-decimals" -> request.setCurrency("KWD");
                case "timezone" -> request.setTimeZone("Mars/Olympus");
                case "tax-null" -> request.setTaxRate(null);
                case "tax-negative" -> request.setTaxRate(new BigDecimal("-0.01"));
                case "tax-over100" -> request.setTaxRate(new BigDecimal("100.01"));
                case "tax-scale" -> request.setTaxRate(new BigDecimal("1.001"));
                case "service-null" -> request.setServiceChargeRate(null);
                case "service-negative" -> request.setServiceChargeRate(new BigDecimal("-0.01"));
                case "service-over100" -> request.setServiceChargeRate(new BigDecimal("100.01"));
                case "service-scale" -> request.setServiceChargeRate(new BigDecimal("1.001"));
                case "logo-http" -> request.setLogoUrl("http://example.test/logo.png");
                case "logo-credentials" -> request.setLogoUrl("https://user:secret@example.test/logo.png");
                case "logo-port0" -> request.setLogoUrl("https://example.test:0/logo.png");
                case "logo-port70000" -> request.setLogoUrl("https://example.test:70000/logo.png");
                case "business-null" -> request.setBusinessName(null);
                case "business-blank" -> request.setBusinessName("  ");
                case "business-long" -> request.setBusinessName("a".repeat(121));
                case "outlet-null" -> request.setOutletName(null);
                case "outlet-blank" -> request.setOutletName("  ");
                case "outlet-long" -> request.setOutletName("a".repeat(121));
                default -> throw new AssertionError("Unknown scenario: " + scenario);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "100"})
    void acceptsInclusiveRateBounds(String value) {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = identicalRequest(fixture);
        request.setTaxRate(new BigDecimal(value));
        request.setServiceChargeRate(new BigDecimal(value));
        MerchantBusinessSettingsResponse response = service.update(owner, outletId, request);
        assertEquals(new BigDecimal(value).setScale(2), response.getTaxRate());
        assertEquals(new BigDecimal(value).setScale(2), response.getServiceChargeRate());
        verify(auditLogs).persist(any(AuditLogEntity.class));
        verifyNoInteractions(settings);
    }

    @Test
    void blankOptionalValuesClearFieldsAndLegacyCredentialLogoIsExcludedFromParseableAudit() throws Exception {
        Fixture fixture = fixture();
        String beforeName = "Before" + (char) 0 + "\b\f\n\t\"\\End";
        String afterName = "After" + (char) 0 + "\b\f\n\t\"\\End";
        fixture.tenant().updateBusinessSettings(beforeName, "https://legacy:credential@example.test/logo.png",
                "IDR", "Jalan Lama", "08123", new BigDecimal("10.00"), new BigDecimal("5.00"), "Asia/Jakarta");
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        request.setBusinessName(afterName);
        request.setLogoUrl("   ");
        request.setAddress(" ");
        request.setPhoneNumber("\t");
        request.setOutletAddress(" ");
        request.setOutletPhoneNumber("\n");

        service.update(owner, outletId, request);

        assertNull(fixture.tenant().getLogoUrl());
        assertNull(fixture.tenant().getAddress());
        assertNull(fixture.tenant().getPhoneNumber());
        assertNull(fixture.outlet().getAddress());
        assertNull(fixture.outlet().getPhoneNumber());
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogs).persist(audit.capture());
        String details = audit.getValue().getDetails();
        assertFalse(details.contains("legacy"));
        assertFalse(details.contains("credential"));
        assertFalse(details.chars().anyMatch(character -> character < 0x20));
        JsonNode parsed = new ObjectMapper().readTree(details);
        assertTrue(parsed.path("before").path("logoUrl").isNull());
        assertTrue(parsed.path("after").path("logoUrl").isNull());
        assertEquals(beforeName, parsed.path("before").path("businessName").asText());
        assertEquals(afterName, parsed.path("after").path("businessName").asText());
    }

    @Test
    void receiptFooterIsTrimmedStoredAndAuditedBeforeAndAfter() throws Exception {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        request.setReceiptFooter("  Terima kasih, sampai jumpa!  ");

        MerchantBusinessSettingsResponse response = service.update(owner, outletId, request);

        assertEquals("Terima kasih, sampai jumpa!", fixture.tenant().getReceiptFooter());
        assertEquals("Terima kasih, sampai jumpa!", response.getReceiptFooter());
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogs).persist(audit.capture());
        JsonNode details = new ObjectMapper().readTree(audit.getValue().getDetails());
        assertTrue(details.path("before").path("receiptFooter").isNull());
        assertEquals("Terima kasih, sampai jumpa!", details.path("after").path("receiptFooter").asText());
    }

    @Test
    void blankReceiptFooterClearsAnExistingFooterAndAudits() throws Exception {
        Fixture fixture = fixture();
        fixture.tenant().changeReceiptFooter("Footer lama");
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        request.setReceiptFooter("   ");

        MerchantBusinessSettingsResponse response = service.update(owner, outletId, request);

        assertNull(fixture.tenant().getReceiptFooter());
        assertNull(response.getReceiptFooter());
        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogs).persist(audit.capture());
        JsonNode details = new ObjectMapper().readTree(audit.getValue().getDetails());
        assertEquals("Footer lama", details.path("before").path("receiptFooter").asText());
        assertTrue(details.path("after").path("receiptFooter").isNull());
    }

    @Test
    void unchangedReceiptFooterWritesNoAudit() {
        Fixture fixture = fixture();
        fixture.tenant().changeReceiptFooter("Sama");
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        request.setReceiptFooter(" Sama ");

        service.update(owner, outletId, request);

        assertEquals("Sama", fixture.tenant().getReceiptFooter());
        verifyNoInteractions(auditLogs);
    }

    @Test
    void receiptFooterLongerThan200CharactersIsRejectedWithoutAudit() {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        request.setReceiptFooter("x".repeat(201));

        assertThrows(InvalidOperationException.class, () -> service.update(owner, outletId, request));

        assertNull(fixture.tenant().getReceiptFooter());
        verifyNoInteractions(auditLogs);
    }

    private void assertRejected(java.util.function.Consumer<MerchantBusinessSettingsRequest> mutation) {
        Fixture fixture = fixture();
        when(guard.requireOwner(owner, outletId)).thenReturn(fixture.context());
        MerchantBusinessSettingsRequest request = validRequest();
        mutation.accept(request);

        assertThrows(InvalidOperationException.class, () -> service.update(owner, outletId, request));
        assertEquals("Kopi Lama", fixture.tenant().getName());
        assertEquals("Outlet Lama", fixture.outlet().getName());
        assertEquals("IDR", fixture.tenant().getCurrency());
        assertEquals("Jalan Lama", fixture.tenant().getAddress());
        assertEquals(new BigDecimal("10.00"), fixture.tenant().getTaxRate());
        assertEquals(new BigDecimal("5.00"), fixture.tenant().getServiceChargeRate());
        assertEquals("Asia/Jakarta", fixture.outlet().getTimeZone());
        verifyNoInteractions(settings, auditLogs);
    }

    private MerchantBusinessSettingsRequest identicalRequest(Fixture fixture) {
        MerchantBusinessSettingsRequest request = new MerchantBusinessSettingsRequest();
        request.setBusinessName(fixture.tenant().getName());
        request.setLogoUrl(fixture.tenant().getLogoUrl());
        request.setCurrency(fixture.tenant().getCurrency());
        request.setAddress(fixture.tenant().getAddress());
        request.setPhoneNumber(fixture.tenant().getPhoneNumber());
        request.setTaxRate(fixture.tenant().getTaxRate());
        request.setServiceChargeRate(fixture.tenant().getServiceChargeRate());
        request.setOutletName(fixture.outlet().getName());
        request.setOutletAddress(fixture.outlet().getAddress());
        request.setOutletPhoneNumber(fixture.outlet().getPhoneNumber());
        request.setTimeZone(fixture.outlet().getTimeZone());
        return request;
    }

    private Fixture fixture() {
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        tenant.updateBusinessSettings("Kopi Lama", null, "IDR", "Jalan Lama", "08123",
                new BigDecimal("10.00"), new BigDecimal("5.00"), "Asia/Jakarta");
        OutletEntity outlet = OutletEntity.open(tenant, "Outlet Lama", "Asia/Jakarta", "Lantai Satu", "08124");
        ReflectionTestUtils.setField(outlet, "id", outletId);
        StaffUserEntity actor = StaffUserEntity.createCashier("owner@example.test", "hash", "Owner", "6281234567890");
        TenantMembershipEntity membership = TenantMembershipEntity.grantOwner(actor, tenant);
        return new Fixture(tenant, outlet, actor, new MerchantAccessContext(tenant, outlet, membership));
    }

    private MerchantBusinessSettingsRequest validRequest() {
        MerchantBusinessSettingsRequest request = new MerchantBusinessSettingsRequest();
        request.setBusinessName("Kopi Lama");
        request.setCurrency("IDR");
        request.setAddress("Jalan Lama");
        request.setPhoneNumber("08123");
        request.setTaxRate(new BigDecimal("10.00"));
        request.setServiceChargeRate(new BigDecimal("5.00"));
        request.setOutletName("Outlet Lama");
        request.setOutletAddress("Lantai Satu");
        request.setOutletPhoneNumber("08124");
        request.setTimeZone("Asia/Jakarta");
        return request;
    }

    private record Fixture(TenantEntity tenant, OutletEntity outlet, StaffUserEntity actor,
                           MerchantAccessContext context) { }
}
