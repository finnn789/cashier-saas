package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.FnbOrderMapper;
import org.lite.project.cashiersaas.dto.request.PublicOrderRequest;
import org.lite.project.cashiersaas.dto.response.PublicOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublicOrderService {
    private static final Duration TOKEN_TTL = Duration.ofDays(30);
    private final PublicQrAccessService publicQrAccess;
    private final FnbCatalogRepository catalog;
    private final FnbOrderRepository orders;
    private final PublicOrderTokenService tokens;

    @Transactional
    public PublicOrderResponse checkout(String qrToken, String key, PublicOrderRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}"))
            throw new InvalidOperationException("Idempotency-Key wajib 16–128 karakter alfanumerik, '_' atau '-'.");
        TableQrTokenEntity qr = publicQrAccess.requirePublicQr(qrToken);
        UUID tenantId = qr.getTenant().getId();
        UUID outletId = qr.getOutlet().getId();
        UUID tableId = qr.getDiningTable().getId();
        String fingerprint = fingerprint(tableId, request);
        orders.lockTenant(tenantId);
        orders.refreshQr(qr);
        // Rotation invalidates the old QR immediately, including in-flight requests queued on the tenant lock.
        if (!qr.isActive() || !qr.getToken().equals(qrToken) || !qr.getDiningTable().isActive()
                || !qr.getOutlet().isActive() || !qr.getTenant().isActive()) throw new ResourceNotFoundException();
        publicQrAccess.requirePublicQr(qrToken);
        String statusToken = tokens.token(tenantId, key);
        var existing = orders.byIdempotency(tenantId, key);
        if (existing.isPresent()) {
            return existingOrder(existing.get(), tableId, fingerprint, statusToken);
        }
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PublicOrderRequest.Item item : request.getItems()) {
            PricedLine line = priceLine(tenantId, outletId, item);
            lines.add(line);
            subtotal = subtotal.add(line.total());
        }
        BigDecimal tax = percentage(subtotal, qr.getTenant().getTaxRate());
        BigDecimal charge = percentage(subtotal, qr.getTenant().getServiceChargeRate());
        String number = "QR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        SalesOrderEntity order = SalesOrderEntity.publicQr(qr.getTenant(), qr.getOutlet(),
                new SalesOrderEntity.PublicQrDetails(qr.getDiningTable(), key, fingerprint, number,
                        tokens.digest(statusToken), Instant.now().plus(TOKEN_TTL), request.getCustomerLabel().trim()),
                new SalesOrderEntity.Charges(subtotal, tax, charge));
        orders.persist(order);
        persistLines(qr, order, lines);
        qr.getDiningTable().markActive();
        orders.flush();
        return FnbOrderMapper.publicOrder(order, statusToken);
    }

    private PublicOrderResponse existingOrder(SalesOrderEntity previous, UUID tableId, String fingerprint,
                                              String statusToken) {
        if (!fingerprint.equals(previous.getRequestFingerprint()) || previous.getSource() != OrderSource.QR
                || !tableId.equals(previous.getDiningTable().getId()))
            throw new OperationConflictException("Idempotency-Key telah digunakan untuk pesanan berbeda.");
        if (previous.getPublicStatusTokenExpiresAt().isBefore(Instant.now()))
            throw new OperationConflictException("Token status pesanan telah kedaluwarsa.");
        if (!tokens.digest(statusToken).equals(previous.getPublicStatusTokenDigest()))
            throw new OperationConflictException("Token status tidak lagi dapat direkonstruksi.");
        return FnbOrderMapper.publicOrder(previous, statusToken);
    }

    private void persistLines(TableQrTokenEntity qr, SalesOrderEntity order, List<PricedLine> lines) {
        for (PricedLine line : lines) {
            OrderItemEntity saved = OrderItemEntity.snapshot(qr.getTenant(), qr.getOutlet(), order,
                    line.product(), line.request().getQuantity(), line.total(), line.request().getNote());
            orders.persist(saved);
            for (ProductOptionEntity option : line.options())
                orders.persist(OrderItemOptionEntity.snapshot(qr.getTenant(), qr.getOutlet(), saved, option));
        }
    }

    @Transactional(readOnly = true)
    public PublicOrderResponse status(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ResourceNotFoundException();
        SalesOrderEntity order = orders.byStatusDigest(tokens.digest(token)).orElseThrow(ResourceNotFoundException::new);
        if (order.getPublicStatusTokenExpiresAt() == null || !order.getPublicStatusTokenExpiresAt().isAfter(Instant.now()))
            throw new ResourceNotFoundException();
        return FnbOrderMapper.publicOrder(order, null);
    }

    private PricedLine priceLine(UUID tenantId, UUID outletId, PublicOrderRequest.Item item) {
        ProductEntity product = catalog.product(tenantId, outletId, item.getProductId())
                .orElseThrow(() -> new InvalidOperationException("Produk tidak tersedia."));
        catalog.lock(product);
        catalog.lock(product.getCategory());
        if (!product.isAvailable() || product.isArchived() || product.getCategory().isArchived())
            throw new InvalidOperationException("Produk tidak tersedia.");
        Set<UUID> selectedIds = new HashSet<>(item.getOptionIds());
        if (selectedIds.size() != item.getOptionIds().size()) throw new InvalidOperationException("Pilihan tidak boleh berulang.");
        List<ProductOptionEntity> selected = selectedOptions(tenantId, outletId, product, selectedIds);
        if (selected.size() != selectedIds.size()) throw new InvalidOperationException("Pilihan bukan milik produk.");
        BigDecimal unit = product.getPrice();
        for (ProductOptionEntity option : selected) unit = unit.add(option.getPriceAdjustment());
        return new PricedLine(item, product, selected, unit.multiply(BigDecimal.valueOf(item.getQuantity())));
    }
    private List<ProductOptionEntity> selectedOptions(UUID tenantId, UUID outletId, ProductEntity product,
                                                       Set<UUID> selectedIds) {
        List<ProductOptionEntity> selected = new ArrayList<>();
        for (OptionGroupEntity group : catalog.groups(tenantId, outletId, product.getId())) {
            catalog.lock(group);
            int count = 0;
            for (ProductOptionEntity option : catalog.options(tenantId, outletId, group.getId())) {
                if (selectedIds.contains(option.getId())) {
                    catalog.lock(option);
                    if (!option.isAvailable()) throw new InvalidOperationException("Pilihan tidak tersedia.");
                    selected.add(option);
                    count++;
                }
            }
            if (count < group.getMinimumSelections() || count > group.getMaximumSelections())
                throw new InvalidOperationException("Jumlah pilihan pada grup tidak valid.");
        }
        return selected;
    }
    private BigDecimal percentage(BigDecimal value, BigDecimal percent) {
        return value.multiply(percent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
    private String fingerprint(UUID tableId, PublicOrderRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(tableId.toString());
            out.writeUTF(request.getCustomerLabel().trim());
            out.writeInt(request.getItems().size());
            for (PublicOrderRequest.Item item : request.getItems()) {
                out.writeUTF(item.getProductId().toString());
                out.writeInt(item.getQuantity());
                out.writeUTF(item.getNote() == null ? "" : item.getNote());
                List<UUID> ids = item.getOptionIds().stream().sorted(Comparator.naturalOrder()).toList();
                out.writeInt(ids.size());
                for (UUID id : ids) out.writeUTF(id.toString());
            }
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint checkout", exception);
        }
    }
    private record PricedLine(PublicOrderRequest.Item request, ProductEntity product,
                              List<ProductOptionEntity> options, BigDecimal total) { }
}
