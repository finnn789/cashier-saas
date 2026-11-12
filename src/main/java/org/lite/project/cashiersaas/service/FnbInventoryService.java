package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbInventoryMapper;
import org.lite.project.cashiersaas.dto.request.FnbInventoryItemRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockMovementRequest;
import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FnbInventoryService {
    private final MerchantAccessGuard guard;
    private final FnbInventoryRepository inventory;
    private final DigestService tokens;

    @Transactional
    public FnbInventoryItemResponse createItem(TenantPrincipal principal, UUID outletId, FnbInventoryItemRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        UUID tenantId = principal.getTenantId();
        InventoryItemEntity item = InventoryItemEntity.create(context.getTenant(), context.getOutlet(),
                sku(request), request.getName().trim(), request.getUnit().trim(), request.getReorderLevel());
        inventory.persist(item);
        inventory.flush();
        InventoryBalanceEntity balance = InventoryBalanceEntity.create(tenantId, outletId, item);
        inventory.persist(balance);
        BigDecimal initial = request.getInitialQuantity();
        if (initial != null && initial.signum() > 0) {
            String reason = "Stok awal";
            balance.apply(initial);
            inventory.persist(InventoryMovementEntity.record(tenantId, outletId, item, null,
                    InventoryMovementEntity.Type.RESTOCK, initial, reason, "init:" + item.getId(),
                    fingerprint(InventoryMovementEntity.Type.RESTOCK, item.getId(), initial, reason),
                    context.getMembership().getId()));
        }
        return FnbInventoryMapper.item(item, balance);
    }

    @Transactional
    public FnbInventoryItemResponse updateItem(TenantPrincipal principal, UUID outletId, UUID id, FnbInventoryItemRequest request) {
        guard.requireOwner(principal, outletId);
        UUID tenantId = principal.getTenantId();
        InventoryItemEntity item = item(tenantId, outletId, id);
        String unit = request.getUnit().trim();
        if (!item.getUnit().equals(unit) && !inventory.movements(tenantId, outletId, id, 0, 1).isEmpty())
            throw new InvalidOperationException("Satuan tidak dapat diubah karena bahan sudah memiliki riwayat stok.");
        item.update(sku(request), request.getName().trim(), unit, request.getReorderLevel());
        inventory.flush();
        return FnbInventoryMapper.item(item, inventory.balance(tenantId, outletId, id).orElse(null));
    }

    @Transactional
    public FnbInventoryItemResponse deactivateItem(TenantPrincipal principal, UUID outletId, UUID id) {
        guard.requireOwner(principal, outletId);
        UUID tenantId = principal.getTenantId();
        InventoryItemEntity item = item(tenantId, outletId, id);
        item.deactivate();
        return FnbInventoryMapper.item(item, inventory.balance(tenantId, outletId, id).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<FnbInventoryItemResponse> items(TenantPrincipal principal, UUID outletId, InventoryBalanceEntity.Status status) {
        guard.requireOutlet(principal, outletId);
        List<InventoryItemEntity> items = inventory.items(principal.getTenantId(), outletId);
        Map<UUID, InventoryBalanceEntity> balances = inventory.balances(principal.getTenantId(), outletId,
                        items.stream().map(InventoryItemEntity::getId).toList()).stream()
                .collect(Collectors.toMap(balance -> balance.getInventoryItem().getId(), Function.identity()));
        return items.stream().map(item -> FnbInventoryMapper.item(item, balances.get(item.getId())))
                .filter(response -> status == null || response.getStockStatus() == status).toList();
    }

    @Transactional
    public FnbStockMovementResponse recordMovement(TenantPrincipal principal, UUID outletId, UUID itemId, FnbStockMovementRequest request) {
        InventoryMovementEntity.Type type = request.getType();
        MerchantAccessContext context = type == InventoryMovementEntity.Type.ADJUSTMENT
                ? guard.requireOwner(principal, outletId) : guard.requireOutlet(principal, outletId);
        UUID tenantId = principal.getTenantId();
        BigDecimal delta = delta(type, request.getQuantity());
        String reason = reason(type, request.getReason());
        InventoryItemEntity item = item(tenantId, outletId, itemId);
        String fingerprint = fingerprint(type, itemId, delta, reason);
        InventoryMovementEntity existing = inventory.movementByKey(tenantId, request.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint))
                throw new OperationConflictException("Kunci idempotensi telah digunakan.");
            return FnbInventoryMapper.movement(existing);
        }
        InventoryBalanceEntity balance = inventory.balance(tenantId, outletId, itemId).orElse(null);
        if (balance == null) {
            balance = InventoryBalanceEntity.create(tenantId, outletId, item);
            inventory.persist(balance);
        } else {
            inventory.lock(balance);
        }
        balance.apply(delta);
        InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null, type, delta,
                reason, request.getIdempotencyKey(), fingerprint, context.getMembership().getId());
        inventory.persist(movement);
        inventory.flush();
        return FnbInventoryMapper.movement(movement);
    }

    @Transactional(readOnly = true)
    public List<FnbStockMovementResponse> movements(TenantPrincipal principal, UUID outletId, UUID itemId, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        guard.requireOutlet(principal, outletId);
        item(principal.getTenantId(), outletId, itemId);
        return inventory.movements(principal.getTenantId(), outletId, itemId, page, size).stream()
                .map(FnbInventoryMapper::movement).toList();
    }

    private InventoryItemEntity item(UUID tenant, UUID outlet, UUID id) {
        return inventory.item(tenant, outlet, id).orElseThrow(ResourceNotFoundException::new);
    }

    private String sku(FnbInventoryItemRequest request) {
        return request.getSku() == null || request.getSku().isBlank() ? null : request.getSku().trim();
    }

    private BigDecimal delta(InventoryMovementEntity.Type type, BigDecimal quantity) {
        if (type == InventoryMovementEntity.Type.SALE)
            throw new InvalidOperationException("Mutasi penjualan dibuat otomatis oleh sistem.");
        if (quantity.signum() == 0) throw new InvalidOperationException("Jumlah tidak boleh nol.");
        if (type == InventoryMovementEntity.Type.ADJUSTMENT) return quantity;
        if (quantity.signum() < 0) throw new InvalidOperationException("Jumlah harus lebih dari nol.");
        return type == InventoryMovementEntity.Type.WASTE ? quantity.negate() : quantity;
    }

    private String reason(InventoryMovementEntity.Type type, String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (!trimmed.isEmpty()) return trimmed;
        if (type == InventoryMovementEntity.Type.RESTOCK) return "Restock";
        throw new InvalidOperationException("Alasan wajib diisi.");
    }

    private String fingerprint(InventoryMovementEntity.Type type, UUID itemId, BigDecimal delta, String reason) {
        return tokens.digest(type + "|" + itemId + "|" + delta.stripTrailingZeros().toPlainString() + "|" + reason);
    }
}
