package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbStaffOrderMapper;
import org.lite.project.cashiersaas.dto.request.FnbPosOrderRequest;
import org.lite.project.cashiersaas.dto.response.FnbStaffOrderResponse;
import org.lite.project.cashiersaas.entity.fnb.DiningTableEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.FnbTableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbPosOrderService {
    private final MerchantAccessGuard guard;
    private final FnbTableRepository tables;
    private final FnbOrderRepository orders;
    private final FnbOrderPricingService pricing;
    private final FnbRealtimePublisher realtime;

    @Transactional
    public FnbStaffOrderResponse checkout(TenantPrincipal principal, UUID outletId, String key,
                                          FnbPosOrderRequest request) {
        MerchantAccessContext context = guard.requireOutlet(principal, outletId);
        String idempotencyKey = validatedKey(key);
        String fingerprint = fingerprint(request);
        orders.lockTenant(context.getTenant().getId());
        context = guard.requireOutlet(principal, outletId);
        if (!context.getTenant().isActive() || !context.getTenant().isFnbEnabled() || !context.getOutlet().isActive())
            throw new ResourceNotFoundException();
        DiningTableEntity table = selectedTable(context, request);
        SalesOrderEntity existing = orders.byIdempotency(context.getTenant().getId(), idempotencyKey).orElse(null);
        if (existing != null) return replay(existing, outletId, fingerprint);
        FnbOrderPricingService.PricedOrder priced = pricing.price(context.getTenant().getId(), outletId,
                request.getItems().stream().map(item -> new FnbOrderPricingService.LineInput(item.getProductId(),
                        item.getQuantity(), item.getNote(), item.getOptionIds())).toList(),
                context.getTenant().getTaxRate(), context.getTenant().getServiceChargeRate());
        String number = "POS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        SalesOrderEntity order = SalesOrderEntity.pos(context.getTenant(), context.getOutlet(),
                new SalesOrderEntity.PosDetails(table, idempotencyKey, fingerprint, number,
                        request.getServiceType(), request.getCustomerLabel().trim()),
                new SalesOrderEntity.Charges(priced.subtotal(), priced.taxAmount(), priced.serviceChargeAmount()));
        order.snapshotRates(context.getTenant().getTaxRate(), context.getTenant().getServiceChargeRate());
        orders.persist(order);
        persistLines(context, order, priced.lines());
        if (table != null) table.markAwaitingPayment();
        orders.flush();
        realtime.orderChanged("ORDER_CREATED", order);
        return response(order);
    }

    private DiningTableEntity selectedTable(MerchantAccessContext context, FnbPosOrderRequest request) {
        if (request.getServiceType() == null) throw new InvalidOperationException("Tipe layanan wajib diisi.");
        if (request.getServiceType() == ServiceType.TAKEAWAY) {
            if (request.getTableId() != null) throw new InvalidOperationException("Takeaway tidak dapat memakai meja.");
            return null;
        }
        if (request.getServiceType() != ServiceType.DINE_IN) throw new InvalidOperationException("Tipe layanan tidak valid.");
        if (request.getTableId() == null) return null;
        DiningTableEntity table = tables.find(context.getTenant().getId(), context.getOutlet().getId(), request.getTableId())
                .orElseThrow(ResourceNotFoundException::new);
        if (!table.isActive()) throw new InvalidOperationException("Meja tidak tersedia.");
        return table;
    }

    private FnbStaffOrderResponse replay(SalesOrderEntity existing, UUID outletId, String fingerprint) {
        if (existing.getSource() != OrderSource.POS || !existing.getOutlet().getId().equals(outletId)
                || !fingerprint.equals(existing.getRequestFingerprint())) {
            throw new OperationConflictException("Idempotency-Key telah digunakan untuk pesanan berbeda.");
        }
        return response(existing);
    }

    private void persistLines(MerchantAccessContext context, SalesOrderEntity order,
                              List<FnbOrderPricingService.PricedLine> lines) {
        for (FnbOrderPricingService.PricedLine line : lines) {
            OrderItemEntity item = OrderItemEntity.snapshot(context.getTenant(), context.getOutlet(), order,
                    line.product(), line.input().quantity(), line.lineTotal(), line.input().note());
            orders.persist(item);
            for (ProductOptionEntity option : line.options())
                orders.persist(OrderItemOptionEntity.snapshot(context.getTenant(), context.getOutlet(), item, option));
        }
    }

    private FnbStaffOrderResponse response(SalesOrderEntity order) {
        List<OrderItemEntity> items = orders.items(order.getTenant().getId(), order.getOutlet().getId(), List.of(order.getId()));
        List<UUID> itemIds = items.stream().map(OrderItemEntity::getId).toList();
        List<OrderItemOptionEntity> options = orders.itemOptions(order.getTenant().getId(), order.getOutlet().getId(), itemIds);
        return FnbStaffOrderMapper.order(order, items, options);
    }

    private String validatedKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}"))
            throw new InvalidOperationException("Idempotency-Key wajib 16-128 karakter alfanumerik, '_' atau '-'.");
        return key;
    }

    private String fingerprint(FnbPosOrderRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.getServiceType() == null ? null : request.getServiceType().name());
        append(canonical, request.getTableId() == null ? null : request.getTableId().toString());
        append(canonical, request.getCustomerLabel() == null ? null : request.getCustomerLabel().trim());
        if (request.getItems() == null) throw new InvalidOperationException("Pesanan harus memiliki item.");
        for (FnbPosOrderRequest.Item item : request.getItems()) {
            append(canonical, item.getProductId() == null ? null : item.getProductId().toString());
            append(canonical, Integer.toString(item.getQuantity()));
            append(canonical, item.getNote());
            if (item.getOptionIds() == null) throw new InvalidOperationException("Pilihan item tidak valid.");
            List<UUID> optionIds = item.getOptionIds().stream().sorted(Comparator.naturalOrder()).toList();
            append(canonical, Integer.toString(optionIds.size()));
            for (UUID optionId : optionIds) append(canonical, optionId.toString());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void append(StringBuilder builder, String value) {
        String part = value == null ? "" : value;
        builder.append(part.length()).append(':').append(part);
    }
}
