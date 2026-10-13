package org.lite.project.cashiersaas.unit.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.dto.FnbMenuMapper;
import org.lite.project.cashiersaas.dto.response.PublicMenuResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class FnbMenuMapperTest {
    @Test
    void outletMenuSupportsDineInAndTakeawayAndOmitsAbsentOptionalFieldsInJson() throws Exception {
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "taxRate", new BigDecimal("11.00"));
        ReflectionTestUtils.setField(tenant, "serviceChargeRate", new BigDecimal("5.00"));
        OutletEntity outlet = OutletEntity.open(tenant, "Main", "Asia/Jakarta", null, null);
        TableQrTokenEntity qr = TableQrTokenEntity.outlet(tenant, outlet, "A".repeat(43));
        CategoryEntity category = CategoryEntity.create(tenant, outlet, "Makanan", 1);
        ProductEntity product = ProductEntity.create(tenant, outlet,
                new ProductEntity.Details(category, "Nasi", null, new BigDecimal("25000.00"), null, true, 1));
        ReflectionTestUtils.setField(category, "id", java.util.UUID.randomUUID());
        ReflectionTestUtils.setField(product, "id", java.util.UUID.randomUUID());

        PublicMenuResponse response = FnbMenuMapper.menu(qr, List.of(category), List.of(product), List.of(), List.of());
        String json = new ObjectMapper().writeValueAsString(response);

        assertEquals(List.of(ServiceType.DINE_IN, ServiceType.TAKEAWAY), response.getServiceTypes());
        assertEquals(new BigDecimal("11.00"), response.getTaxRate());
        assertEquals(new BigDecimal("5.00"), response.getServiceChargeRate());
        assertEquals("Makanan", response.getCategories().get(0).getName());
        assertEquals(new BigDecimal("25000.00"), response.getCategories().get(0).getProducts().get(0).getPrice());
        assertFalse(json.contains("\"tableName\""));
        assertFalse(json.contains("\"description\""));
        assertFalse(json.contains("\"imageUrl\""));
        assertTrue(json.contains("\"serviceTypes\""));
    }

    @Test
    void tableMenuIsDineInOnlyAndPreservesGroupsAndOptions() {
        TenantEntity tenant = new TenantEntity();
        OutletEntity outlet = OutletEntity.open(tenant, "Main", "Asia/Jakarta", null, null);
        org.lite.project.cashiersaas.entity.fnb.DiningTableEntity table =
                org.lite.project.cashiersaas.entity.fnb.DiningTableEntity.create(tenant, outlet, "A1");
        TableQrTokenEntity qr = TableQrTokenEntity.create(tenant, outlet, table, "B".repeat(43));
        CategoryEntity category = CategoryEntity.create(tenant, outlet, "Makanan", 1);
        ProductEntity product = ProductEntity.create(tenant, outlet,
                new ProductEntity.Details(category, "Nasi", "Hangat", new BigDecimal("25000.00"), "/nasi.png", true, 1));
        org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity group =
                org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity.create(tenant, outlet, product,
                        new org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity.Details("Level", true, 1, 1, 1));
        org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity option =
                org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity.create(tenant, outlet, group,
                        "Pedas", new BigDecimal("2000.00"), true, 1);
        ReflectionTestUtils.setField(category, "id", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        ReflectionTestUtils.setField(product, "id", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        ReflectionTestUtils.setField(group, "id", UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        ReflectionTestUtils.setField(option, "id", UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));

        PublicMenuResponse response = FnbMenuMapper.menu(qr, List.of(category), List.of(product),
                List.of(group), List.of(option));

        assertEquals(List.of(ServiceType.DINE_IN), response.getServiceTypes());
        assertEquals("A1", response.getTableName());
        PublicMenuResponse.OptionGroup mappedGroup = response.getCategories().get(0).getProducts().get(0).getGroups().get(0);
        assertTrue(mappedGroup.isRequired());
        assertEquals(1, mappedGroup.getMinimumSelections());
        assertEquals("Pedas", mappedGroup.getOptions().get(0).getName());
        assertEquals(new BigDecimal("2000.00"), mappedGroup.getOptions().get(0).getPriceAdjustment());
    }
}
