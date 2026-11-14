package org.lite.project.cashiersaas.unit.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.dto.MerchantBusinessSettingsMapper;
import org.lite.project.cashiersaas.dto.response.MerchantBusinessSettingsResponse;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MerchantBusinessSettingsMapperTest {
    @Test
    void mapsEveryPopulatedBusinessAndOutletFieldFromItsOwningEntity() {
        TenantEntity tenant = new TenantEntity();
        tenant.updateBusinessSettings("Kopi Nusantara", "https://example.test/logo.png", "USD", "Business road",
                "08111", new BigDecimal("11.00"), new BigDecimal("5.00"), "Asia/Jakarta");
        OutletEntity outlet = OutletEntity.open(tenant, "Menteng", "Asia/Makassar", "Outlet road", "08222");
        UUID outletId = UUID.randomUUID();
        ReflectionTestUtils.setField(outlet, "id", outletId);

        MerchantBusinessSettingsResponse response = MerchantBusinessSettingsMapper.response(tenant, outlet);

        assertEquals(outletId, response.getOutletId());
        assertEquals("Kopi Nusantara", response.getBusinessName());
        assertEquals("https://example.test/logo.png", response.getLogoUrl());
        assertEquals("USD", response.getCurrency());
        assertEquals("Business road", response.getAddress());
        assertEquals("08111", response.getPhoneNumber());
        assertEquals(new BigDecimal("11.00"), response.getTaxRate());
        assertEquals(new BigDecimal("5.00"), response.getServiceChargeRate());
        assertEquals("Menteng", response.getOutletName());
        assertEquals("Outlet road", response.getOutletAddress());
        assertEquals("08222", response.getOutletPhoneNumber());
        assertEquals("Asia/Makassar", response.getTimeZone());
        assertNull(response.getReceiptFooter());
    }

    @Test
    void mapsTheReceiptFooter() {
        TenantEntity tenant = new TenantEntity();
        tenant.updateBusinessSettings("Kopi Nusantara", null, "IDR", null, null,
                new BigDecimal("11.00"), new BigDecimal("5.00"), "Asia/Jakarta");
        tenant.changeReceiptFooter("Terima kasih");
        OutletEntity outlet = OutletEntity.open(tenant, "Menteng", "Asia/Jakarta", null, null);

        assertEquals("Terima kasih", MerchantBusinessSettingsMapper.response(tenant, outlet).getReceiptFooter());
    }

    @Test
    void nullOptionalFieldsStayNullAndAreOmittedFromJson() throws Exception {
        TenantEntity tenant = new TenantEntity();
        UUID outletId = UUID.randomUUID();
        tenant.updateBusinessSettings("Kopi Nusantara", null, "IDR", null, null,
                new BigDecimal("11.00"), new BigDecimal("5.00"), "Asia/Jakarta");
        OutletEntity outlet = OutletEntity.open(tenant, "Menteng", "Asia/Jakarta", null, null);
        ReflectionTestUtils.setField(outlet, "id", outletId);

        MerchantBusinessSettingsResponse response = MerchantBusinessSettingsMapper.response(tenant, outlet);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertNull(response.getLogoUrl());
        assertNull(response.getAddress());
        assertNull(response.getPhoneNumber());
        assertNull(response.getOutletAddress());
        assertNull(response.getOutletPhoneNumber());
        assertFalse(json.has("logoUrl"));
        assertFalse(json.has("address"));
        assertFalse(json.has("phoneNumber"));
        assertFalse(json.has("outletAddress"));
        assertFalse(json.has("outletPhoneNumber"));
        assertEquals(outletId.toString(), json.path("outletId").asText());
        assertEquals("Kopi Nusantara", json.path("businessName").asText());
        assertTrue(json.has("taxRate"));
        assertTrue(json.has("serviceChargeRate"));
    }
}
