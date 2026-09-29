package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.dto.request.CashierSaleLineRequest;
import org.lite.project.cashiersaas.dto.request.CashierSaleRequest;
import org.lite.project.cashiersaas.dto.response.CashierSaleResponse;
import org.lite.project.cashiersaas.entity.laundry.CashierCatalogItemEntity;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleEntity;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleItemEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;

import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.CashierCatalogRepository;
import org.lite.project.cashiersaas.repository.CashierSaleRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.lite.project.cashiersaas.service.CashierSaleService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class CashierSaleServiceTest {
    @Mock private MerchantReferenceRepository references;
    @Mock private CashierCatalogRepository catalogRepository;
    @Mock private CashierSaleRepository saleRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private MerchantAccessGuard merchantAccessGuard;
    @InjectMocks private CashierSaleService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));
    private TenantEntity tenant;
    private OutletEntity outlet;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity();
        outlet = new OutletEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        ReflectionTestUtils.setField(outlet, "id", outletId);
        lenient().when(references.tenant(tenantId)).thenReturn(tenant);
        lenient().when(references.outlet(outletId)).thenReturn(outlet);
        lenient().when(references.user(principal.getUserId())).thenReturn(
                StaffUserEntity.createCashier("cashier@example.com", "hash", "Kasir"));
        lenient().when(merchantAccessGuard.requireOutlet(principal, outletId))
                .thenReturn(new MerchantAccessContext(tenant, outlet, null));
    }

    @Test
    void checkoutCalculatesTotalAndChangeFromCatalogAndKeepsPriceSnapshot() {
        allowOutlet();
        CashierCatalogItemEntity item = catalogItem("Cuci kiloan", "KG", "7500.00");
        when(catalogRepository.findActiveByIds(tenantId, outletId, Set.of(itemId))).thenReturn(List.of(item));
        CashierSaleRequest request = request("2.500", "CASH", "20000.00");

        CashierSaleResponse response = service.checkout(principal, outletId, "sale-1", request);

        ArgumentCaptor<CashierSaleEntity> saleCaptor = ArgumentCaptor.forClass(CashierSaleEntity.class);
        ArgumentCaptor<CashierSaleItemEntity> lineCaptor = ArgumentCaptor.forClass(CashierSaleItemEntity.class);
        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(saleRepository).persist(saleCaptor.capture());
        verify(saleRepository).persist(lineCaptor.capture());
        verify(auditLogRepository).persist(auditCaptor.capture());
        assertEquals(new BigDecimal("18750.00"), response.getTotal());
        assertEquals(new BigDecimal("1250.00"), response.getChangeAmount());
        assertEquals("PAID", response.getPaymentStatus());
        assertEquals("Pelanggan", response.getCustomerName());
        assertEquals(new BigDecimal("18750.00"), saleCaptor.getValue().getTotalAmount());
        assertEquals("CASH", saleCaptor.getValue().getPaymentMethod());
        assertEquals(new BigDecimal("20000.00"), saleCaptor.getValue().getTenderedAmount());
        assertEquals("sale-1", saleCaptor.getValue().getIdempotencyKey());
        assertEquals(64, saleCaptor.getValue().getRequestFingerprint().length());
        assertNotNull(saleCaptor.getValue().getReceivedByMembershipId());
        assertEquals("Cuci kiloan", lineCaptor.getValue().getNameSnapshot());
        assertEquals(new BigDecimal("7500.00"), lineCaptor.getValue().getUnitPriceSnapshot());
        assertEquals("CASHIER_PAYMENT_RECORDED", auditCaptor.getValue().getAction());
        assertEquals("CASHIER_SALE", auditCaptor.getValue().getEntityType());
        assertEquals("amount=18750.00;method=CASH", auditCaptor.getValue().getDetails());
        item.changePrice(new BigDecimal("9000.00"));
        assertEquals(new BigDecimal("7500.00"), lineCaptor.getValue().getUnitPriceSnapshot());
    }

    @Test
    void checkoutLoadsAllRequestedCatalogItemsTogetherAndPreservesLineOrder() {
        UUID secondItemId = UUID.randomUUID();
        CashierCatalogItemEntity firstItem = catalogItem("Pertama", "PIECE", "10000.00");
        CashierCatalogItemEntity secondItem = CashierCatalogItemEntity.create(tenant, outlet,
                "Kedua", "PIECE", new BigDecimal("5000.00"));
        ReflectionTestUtils.setField(secondItem, "id", secondItemId);
        when(catalogRepository.findActiveByIds(tenantId, outletId, Set.of(itemId, secondItemId)))
                .thenReturn(List.of(secondItem, firstItem));
        CashierSaleRequest request = request("1", "CASH", "20000.00");
        CashierSaleLineRequest secondLine = new CashierSaleLineRequest();
        secondLine.setCatalogItemId(secondItemId);
        secondLine.setQuantity(BigDecimal.ONE);
        request.setItems(List.of(request.getItems().get(0), secondLine));

        CashierSaleResponse response = service.checkout(principal, outletId, "sale-two-items", request);

        verify(catalogRepository).findActiveByIds(tenantId, outletId, Set.of(itemId, secondItemId));
        assertEquals(new BigDecimal("15000.00"), response.getTotal());
        assertEquals(List.of("Pertama", "Kedua"), response.getItems().stream()
                .map(item -> item.getName()).toList());
    }

    @Test
    void retryWithSameKeyAndRequestReturnsExistingSaleWithoutRecordingAnotherPayment() {
        allowOutlet();
        CashierSaleRequest request = request("1", "CASH", "10000.00");
        CashierCatalogItemEntity item = catalogItem("Cuci", "PIECE", "10000.00");
        when(catalogRepository.findActiveByIds(tenantId, outletId, Set.of(itemId))).thenReturn(List.of(item));
        CashierSaleResponse first = service.checkout(principal, outletId, "sale-1", request);
        ArgumentCaptor<CashierSaleEntity> saleCaptor = ArgumentCaptor.forClass(CashierSaleEntity.class);
        verify(saleRepository).persist(saleCaptor.capture());
        CashierSaleEntity existing = saleCaptor.getValue();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(saleRepository.findByKey(tenantId, "sale-1")).thenReturn(Optional.of(existing));
        when(saleRepository.listItems(eq(tenantId), eq(outletId), any())).thenReturn(List.of());

        CashierSaleResponse retry = service.checkout(principal, outletId, "sale-1", request);

        assertEquals(first.getTotal(), retry.getTotal());
        assertEquals(first.getSaleNumber(), retry.getSaleNumber());
        verify(saleRepository, org.mockito.Mockito.times(1)).persist(any(CashierSaleEntity.class));
        verify(auditLogRepository, org.mockito.Mockito.times(1)).persist(any(AuditLogEntity.class));
    }

    @Test
    void reusedKeyForDifferentSaleIsRejectedBeforeAnyWrite() {
        allowOutlet();
        CashierSaleRequest original = request("1", "CASH", "10000.00");
        when(catalogRepository.findActiveByIds(tenantId, outletId, Set.of(itemId)))
                .thenReturn(List.of(catalogItem("Cuci", "PIECE", "10000.00")));
        service.checkout(principal, outletId, "sale-1", original);
        ArgumentCaptor<CashierSaleEntity> saleCaptor = ArgumentCaptor.forClass(CashierSaleEntity.class);
        verify(saleRepository).persist(saleCaptor.capture());
        when(saleRepository.findByKey(tenantId, "sale-1")).thenReturn(Optional.of(saleCaptor.getValue()));

        CashierSaleRequest changed = request("1", "CASH", "12000.00");
        assertThrows(OperationConflictException.class, () -> service.checkout(principal, outletId, "sale-1", changed));
        verify(saleRepository, org.mockito.Mockito.times(1)).persist(any(CashierSaleEntity.class));
    }

    @Test
    void unavailableItemCannotBeCheckedOut() {
        allowOutlet();
        when(catalogRepository.findActiveByIds(tenantId, outletId, Set.of(itemId))).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class,
                () -> service.checkout(principal, outletId, "sale-1", request("1", "CASH", "10000.00")));
        verify(saleRepository, never()).persist(any(CashierSaleEntity.class));
        verify(auditLogRepository, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void pieceQuantityMustBeWholeAndNonCashTenderMustEqualTotal() {
        allowOutlet();
        when(catalogRepository.findActiveByIds(tenantId, outletId, Set.of(itemId)))
                .thenReturn(List.of(catalogItem("Setrika", "PIECE", "10000.00")));

        assertThrows(InvalidOperationException.class,
                () -> service.checkout(principal, outletId, "sale-1", request("1.5", "CASH", "20000.00")));
        assertThrows(InvalidOperationException.class,
                () -> service.checkout(principal, outletId, "sale-2", request("1", "NON_CASH", "20000.00")));
        verify(saleRepository, never()).persist(any(CashierSaleEntity.class));
    }

    @Test
    void saleLookupUsesTenantAndOutletFromPrincipalAndRejectsMissingSale() {
        when(saleRepository.findById(tenantId, outletId, itemId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(principal, outletId, itemId));
        verify(saleRepository).findById(tenantId, outletId, itemId);
    }

    @Test
    void listLoadsItemsForAllSalesInOneTenantScopedLookup() {
        CashierSaleEntity firstSale = sale("Pertama");
        CashierSaleEntity secondSale = sale("Kedua");
        UUID firstSaleId = UUID.randomUUID();
        UUID secondSaleId = UUID.randomUUID();
        ReflectionTestUtils.setField(firstSale, "id", firstSaleId);
        ReflectionTestUtils.setField(secondSale, "id", secondSaleId);
        CashierSaleItemEntity firstLine = CashierSaleItemEntity.snapshot(firstSale,
                catalogItem("Layanan pertama", "PIECE", "10000.00"), BigDecimal.ONE,
                new BigDecimal("10000.00"));
        CashierSaleItemEntity secondLine = CashierSaleItemEntity.snapshot(secondSale,
                catalogItem("Layanan kedua", "PIECE", "10000.00"), BigDecimal.ONE,
                new BigDecimal("10000.00"));
        when(saleRepository.listRecent(tenantId, outletId)).thenReturn(List.of(firstSale, secondSale));
        when(saleRepository.listItemsForSales(tenantId, outletId, List.of(firstSaleId, secondSaleId)))
                .thenReturn(List.of(secondLine, firstLine));

        List<CashierSaleResponse> responses = service.list(principal, outletId);

        assertEquals(List.of(firstSaleId, secondSaleId), responses.stream()
                .map(CashierSaleResponse::getId).toList());
        assertEquals("Layanan pertama", responses.get(0).getItems().get(0).getName());
        assertEquals("Layanan kedua", responses.get(1).getItems().get(0).getName());
        verify(saleRepository).listItemsForSales(tenantId, outletId, List.of(firstSaleId, secondSaleId));
        verify(saleRepository, never()).listItems(eq(tenantId), eq(outletId), any(UUID.class));
    }

    private CashierSaleEntity sale(String customerName) {
        return CashierSaleEntity.createPaidSale(tenant, outlet, customerName,
                new BigDecimal("10000.00"), "IDR",
                new CashierSaleEntity.PaymentDetails("CASH", new BigDecimal("10000.00"), null),
                new CashierSaleEntity.CheckoutMetadata(principal.getMembershipId(), UUID.randomUUID().toString(),
                        "fingerprint"));
    }

    private void allowOutlet() {
        // The principal's outlet claim is sufficient for ordinary cashier requests.
    }

    private CashierCatalogItemEntity catalogItem(String name, String unit, String price) {
        CashierCatalogItemEntity item = CashierCatalogItemEntity.create(tenant, outlet, name, unit, new BigDecimal(price));
        ReflectionTestUtils.setField(item, "id", itemId);
        return item;
    }

    private CashierSaleRequest request(String quantity, String method, String tendered) {
        CashierSaleLineRequest line = new CashierSaleLineRequest();
        line.setCatalogItemId(itemId);
        line.setQuantity(new BigDecimal(quantity));
        CashierSaleRequest request = new CashierSaleRequest();
        request.setCustomerName(" Pelanggan ");
        request.setItems(List.of(line));
        request.setPaymentMethod(method);
        request.setTenderedAmount(new BigDecimal(tendered));
        return request;
    }
}
