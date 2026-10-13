package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.CashierMapper;
import org.lite.project.cashiersaas.dto.request.CashierSaleLineRequest;
import org.lite.project.cashiersaas.dto.request.CashierSaleRequest;
import org.lite.project.cashiersaas.dto.response.CashierSaleResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.CashierCatalogItemEntity;
import org.lite.project.cashiersaas.entity.shared.sales.CashierSaleEntity;
import org.lite.project.cashiersaas.entity.shared.sales.CashierSaleItemEntity;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.CashierCatalogRepository;
import org.lite.project.cashiersaas.repository.CashierSaleRepository;
import org.lite.project.cashiersaas.repository.MerchantReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CashierSaleService {
    private final MerchantReferenceRepository references;
    private final CashierCatalogRepository catalogRepository;
    private final CashierSaleRepository saleRepository;
    private final AuditLogRepository auditLogRepository;
    private final MerchantAccessGuard merchantAccessGuard;

    @Transactional
    public CashierSaleResponse checkout(TenantPrincipal principal, UUID outletId, String key, CashierSaleRequest request) {
        merchantAccessGuard.requireOutlet(principal, outletId);
        String validatedKey = validatedKey(key);
        String fingerprint = fingerprint(outletId, request);
        CashierSaleEntity existing = saleRepository.findByKey(principal.getTenantId(), validatedKey).orElse(null);
        if (existing != null) {
            validateReplay(existing, fingerprint, outletId);
            return toResponse(existing);
        }

        Set<UUID> requestedIds = request.getItems().stream()
                .map(CashierSaleLineRequest::getCatalogItemId).collect(Collectors.toSet());
        Map<UUID, CashierCatalogItemEntity> activeItems = catalogRepository
                .findActiveByIds(principal.getTenantId(), outletId, requestedIds).stream()
                .collect(Collectors.toMap(CashierCatalogItemEntity::getId, Function.identity()));
        Set<UUID> distinctIds = new HashSet<>();
        List<CashierCatalogItemEntity> catalogItems = new ArrayList<>();
        List<BigDecimal> quantities = new ArrayList<>();
        List<BigDecimal> lineTotals = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (CashierSaleLineRequest line : request.getItems()) {
            validateDistinctItem(distinctIds, line.getCatalogItemId());
            CashierCatalogItemEntity item = activeItems.get(line.getCatalogItemId());
            if (item == null) {
                throw new ResourceNotFoundException("Layanan tidak ditemukan atau tidak tersedia untuk transaksi ini.");
            }
            BigDecimal quantity = line.getQuantity();
            validateQuantity(item, quantity);
            BigDecimal lineTotal = item.getUnitPrice().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            total = total.add(lineTotal);
            catalogItems.add(item);
            quantities.add(quantity);
            lineTotals.add(lineTotal);
        }
        validateTotal(total);
        BigDecimal tendered = request.getTenderedAmount();
        validatePayment(request.getPaymentMethod(), tendered, total);
        CashierSaleEntity.PaymentDetails payment = new CashierSaleEntity.PaymentDetails(
                request.getPaymentMethod(), tendered, trimmedOrNull(request.getPaymentReference()));
        CashierSaleEntity.CheckoutMetadata metadata = new CashierSaleEntity.CheckoutMetadata(
                principal.getMembershipId(), validatedKey, fingerprint);
        CashierSaleEntity sale = CashierSaleEntity.createPaidSale(references.tenant(principal.getTenantId()),
                references.outlet(outletId), request.getCustomerName().trim(), total, "IDR", payment, metadata);
        saleRepository.persist(sale);
        List<CashierSaleItemEntity> lines = new ArrayList<>();
        for (int index = 0; index < catalogItems.size(); index++) {
            CashierSaleItemEntity line = CashierSaleItemEntity.snapshot(sale, catalogItems.get(index),
                    quantities.get(index), lineTotals.get(index));
            saleRepository.persist(line);
            lines.add(line);
        }
        auditLogRepository.persist(AuditLogEntity.recordAction(references.tenant(principal.getTenantId()),
                references.outlet(outletId), references.user(principal.getUserId()),
                AuditLogEntity.Action.CASHIER_PAYMENT_RECORDED, sale.getId(), null,
                "amount=" + total.toPlainString() + ";method=" + request.getPaymentMethod()));
        return CashierMapper.toSaleResponse(sale, lines);
    }

    private void validateReplay(CashierSaleEntity existing, String fingerprint, UUID outletId) {
        if (!existing.getRequestFingerprint().equals(fingerprint) || !existing.getOutlet().getId().equals(outletId)) {
            throw new OperationConflictException("Idempotency key telah dipakai untuk transaksi berbeda.");
        }
    }

    private void validateDistinctItem(Set<UUID> distinctIds, UUID itemId) {
        if (!distinctIds.add(itemId)) {
            throw new InvalidOperationException("Layanan yang sama tidak boleh muncul dua kali.");
        }
    }

    private void validateQuantity(CashierCatalogItemEntity item, BigDecimal quantity) {
        if (quantity.signum() <= 0 || ("PIECE".equals(item.getBillingUnit())
                && quantity.stripTrailingZeros().scale() > 0)) {
            throw new InvalidOperationException("Jumlah layanan tidak sesuai satuannya.");
        }
    }

    private void validateTotal(BigDecimal total) {
        if (total.signum() <= 0) {
            throw new InvalidOperationException("Total transaksi harus lebih dari nol.");
        }
    }

    private void validatePayment(String method, BigDecimal tendered, BigDecimal total) {
        if (tendered.compareTo(total) < 0 || ("NON_CASH".equals(method) && tendered.compareTo(total) != 0)) {
            throw new InvalidOperationException("Nominal diterima tidak sesuai total transaksi.");
        }
    }

    @Transactional(readOnly = true)
    public CashierSaleResponse get(TenantPrincipal principal, UUID outletId, UUID saleId) {
        merchantAccessGuard.requireOutlet(principal, outletId);
        CashierSaleEntity sale = saleRepository.findById(principal.getTenantId(), outletId, saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaksi tidak ditemukan atau tidak dapat diakses."));
        return toResponse(sale);
    }

    @Transactional(readOnly = true)
    public List<CashierSaleResponse> list(TenantPrincipal principal, UUID outletId) {
        merchantAccessGuard.requireOutlet(principal, outletId);
        List<CashierSaleEntity> sales = saleRepository.listRecent(principal.getTenantId(), outletId);
        if (sales.isEmpty()) {
            return List.of();
        }
        List<UUID> saleIds = sales.stream().map(CashierSaleEntity::getId).toList();
        Map<UUID, List<CashierSaleItemEntity>> itemsBySale = saleRepository
                .listItemsForSales(principal.getTenantId(), outletId, saleIds).stream()
                .collect(Collectors.groupingBy(item -> item.getSale().getId()));
        return sales.stream()
                .map(sale -> CashierMapper.toSaleResponse(sale, itemsBySale.getOrDefault(sale.getId(), List.of())))
                .toList();
    }

    private CashierSaleResponse toResponse(CashierSaleEntity sale) {
        return CashierMapper.toSaleResponse(sale,
                saleRepository.listItems(sale.getTenant().getId(), sale.getOutlet().getId(), sale.getId()));
    }

    private String validatedKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InvalidOperationException("Idempotency-Key wajib diisi, maksimal 128 karakter.");
        }
        return key.trim();
    }

    private String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String fingerprint(UUID outletId, CashierSaleRequest request) {
        StringBuilder canonical = new StringBuilder();
        appendPart(canonical, outletId.toString());
        appendPart(canonical, request.getCustomerName().trim());
        appendPart(canonical, request.getPaymentMethod());
        appendPart(canonical, request.getTenderedAmount().stripTrailingZeros().toPlainString());
        appendPart(canonical, trimmedOrNull(request.getPaymentReference()));
        for (CashierSaleLineRequest line : request.getItems()) {
            appendPart(canonical, line.getCatalogItemId().toString());
            appendPart(canonical, line.getQuantity().stripTrailingZeros().toPlainString());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void appendPart(StringBuilder builder, String value) {
        String part = value == null ? "" : value;
        builder.append(part.length()).append(':').append(part);
    }
}
