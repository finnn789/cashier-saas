package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.FnbOrderMapper;
import org.lite.project.cashiersaas.dto.request.PublicOrderRequest;
import org.lite.project.cashiersaas.dto.response.PublicOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.fnb.TableQrTokenEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublicOrderService {
    private static final Duration TOKEN_TTL = Duration.ofDays(30);
    private final PublicQrAccessService publicQrAccess;
    private final FnbOrderRepository orders;
    private final PublicOrderTokenService tokens;
    private final FnbOrderPricingService pricing;
    private final FnbRealtimePublisher realtime;

    @Transactional
    public PublicOrderResponse checkout(String qrToken, String key, PublicOrderRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}"))
            throw new InvalidOperationException("Idempotency-Key wajib 16-128 karakter alfanumerik, '_' atau '-'.");
        TableQrTokenEntity qr = publicQrAccess.requirePublicQr(qrToken);
        UUID tenantId = qr.getTenant().getId();
        UUID outletId = qr.getOutlet().getId();
        UUID tableId = qr.getDiningTable() == null ? null : qr.getDiningTable().getId();
        if (tableId != null && request.getServiceType() != org.lite.project.cashiersaas.enums.ServiceType.DINE_IN)
            throw new InvalidOperationException("QR meja hanya untuk dine-in.");
        String fingerprint = fingerprint(tableId, request);
        orders.lockTenant(tenantId);
        orders.refreshQr(qr);
        // Rotation invalidates the old QR immediately, including in-flight requests queued on the tenant lock.
        if (!qr.isActive() || !qr.getToken().equals(qrToken) || (qr.getDiningTable() != null && !qr.getDiningTable().isActive())
                || !qr.getOutlet().isActive() || !qr.getTenant().isActive()) throw new ResourceNotFoundException();
        publicQrAccess.requirePublicQr(qrToken);
        String statusToken = tokens.token(tenantId, key);
        var existing = orders.byIdempotency(tenantId, key);
        if (existing.isPresent()) {
            if (!existing.get().getOutlet().getId().equals(outletId))
                throw new OperationConflictException("Idempotency-Key telah digunakan.");
            return existingOrder(existing.get(), tableId, fingerprint, statusToken);
        }
        FnbOrderPricingService.PricedOrder priced = pricing.price(tenantId, outletId,
                request.getItems().stream().map(item -> new FnbOrderPricingService.LineInput(item.getProductId(),
                        item.getQuantity(), item.getNote(), item.getOptionIds())).toList(),
                qr.getTenant().getTaxRate(), qr.getTenant().getServiceChargeRate());
        String number = "QR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        SalesOrderEntity order = SalesOrderEntity.publicQr(qr.getTenant(), qr.getOutlet(),
                new SalesOrderEntity.PublicQrDetails(qr.getDiningTable(), key, fingerprint, number,
                        tokens.digest(statusToken), Instant.now().plus(TOKEN_TTL), request.getCustomerLabel().trim()),
                new SalesOrderEntity.Charges(priced.subtotal(), priced.taxAmount(), priced.serviceChargeAmount()));
        order.snapshotRates(qr.getTenant().getTaxRate(), qr.getTenant().getServiceChargeRate());
        order.selectPublicServiceType(request.getServiceType());
        orders.persist(order);
        persistLines(qr, order, priced.lines());
        if (qr.getDiningTable() != null) qr.getDiningTable().markAwaitingPayment();
        orders.flush();
        realtime.orderChanged("ORDER_CREATED", order);
        return FnbOrderMapper.publicOrder(order, statusToken);
    }

    private PublicOrderResponse existingOrder(SalesOrderEntity previous, UUID tableId, String fingerprint,
                                              String statusToken) {
        if (!fingerprint.equals(previous.getRequestFingerprint()) || previous.getSource() != OrderSource.QR
                || !java.util.Objects.equals(tableId, previous.getDiningTable() == null ? null : previous.getDiningTable().getId()))
            throw new OperationConflictException("Idempotency-Key telah digunakan untuk pesanan berbeda.");
        if (previous.getPublicStatusTokenExpiresAt().isBefore(Instant.now()))
            throw new OperationConflictException("Token status pesanan telah kedaluwarsa.");
        if (!tokens.digest(statusToken).equals(previous.getPublicStatusTokenDigest()))
            throw new OperationConflictException("Token status tidak lagi dapat direkonstruksi.");
        return FnbOrderMapper.publicOrder(previous, statusToken);
    }

    private void persistLines(TableQrTokenEntity qr, SalesOrderEntity order, List<FnbOrderPricingService.PricedLine> lines) {
        for (FnbOrderPricingService.PricedLine line : lines) {
            OrderItemEntity saved = OrderItemEntity.snapshot(qr.getTenant(), qr.getOutlet(), order,
                    line.product(), line.input().quantity(), line.lineTotal(), line.input().note());
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

    private String fingerprint(UUID tableId, PublicOrderRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(tableId == null ? "OUTLET:" + request.getServiceType().name() : tableId.toString());
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
}
