package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbInventoryMapper;
import org.lite.project.cashiersaas.dto.request.FnbInventoryItemRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockCountLineRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockCountRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockMovementRequest;
import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountLineResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
                    fingerprint(InventoryMovementEntity.Type.RESTOCK, item.getId(), initial, reason, null),
                    context.getMembership().getId()));
        }
        return FnbInventoryMapper.item(item, balance, showCost(context));
    }

    @Transactional
    public FnbInventoryItemResponse updateItem(TenantPrincipal principal, UUID outletId, UUID id, FnbInventoryItemRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        UUID tenantId = principal.getTenantId();
        InventoryItemEntity item = item(tenantId, outletId, id);
        String unit = request.getUnit().trim();
        if (!item.getUnit().equals(unit) && !inventory.movements(tenantId, outletId, id, 0, 1).isEmpty())
            throw new InvalidOperationException("Satuan tidak dapat diubah karena bahan sudah memiliki riwayat stok.");
        item.update(sku(request), request.getName().trim(), unit, request.getReorderLevel());
        inventory.flush();
        return FnbInventoryMapper.item(item, inventory.balance(tenantId, outletId, id).orElse(null), showCost(context));
    }

    @Transactional
    public FnbInventoryItemResponse deactivateItem(TenantPrincipal principal, UUID outletId, UUID id) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        UUID tenantId = principal.getTenantId();
        InventoryItemEntity item = item(tenantId, outletId, id);
        item.deactivate();
        return FnbInventoryMapper.item(item, inventory.balance(tenantId, outletId, id).orElse(null), showCost(context));
    }

    @Transactional(readOnly = true)
    public List<FnbInventoryItemResponse> items(TenantPrincipal principal, UUID outletId, InventoryBalanceEntity.Status status) {
        boolean showCost = showCost(guard.requireOutlet(principal, outletId));
        List<InventoryItemEntity> items = inventory.items(principal.getTenantId(), outletId);
        Map<UUID, InventoryBalanceEntity> balances = inventory.balances(principal.getTenantId(), outletId,
                        items.stream().map(InventoryItemEntity::getId).toList()).stream()
                .collect(Collectors.toMap(balance -> balance.getInventoryItem().getId(), Function.identity()));
        return items.stream().map(item -> FnbInventoryMapper.item(item, balances.get(item.getId()), showCost))
                .filter(response -> status == null || response.getStockStatus() == status).toList();
    }

    @Transactional
    public FnbStockMovementResponse recordMovement(TenantPrincipal principal, UUID outletId, UUID itemId, FnbStockMovementRequest request) {
        InventoryMovementEntity.Type type = request.getType();
        MerchantAccessContext context = type == InventoryMovementEntity.Type.ADJUSTMENT
                ? guard.requireOwner(principal, outletId) : guard.requireOutlet(principal, outletId);
        UUID tenantId = principal.getTenantId();
        BigDecimal delta = delta(type, request.getQuantity());
        BigDecimal unitCost = request.getUnitCost();
        if (unitCost != null && type != InventoryMovementEntity.Type.RESTOCK)
            throw new InvalidOperationException("Harga beli hanya untuk restock.");
        String reason = reason(type, request.getReason());
        InventoryItemEntity item = item(tenantId, outletId, itemId);
        String fingerprint = fingerprint(type, itemId, delta, reason, unitCost);
        InventoryMovementEntity existing = inventory.movementByKey(tenantId, request.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint))
                throw new OperationConflictException("Kunci idempotensi telah digunakan.");
            return FnbInventoryMapper.movement(existing, showCost(context));
        }
        InventoryBalanceEntity balance = lockedBalance(tenantId, outletId, item);
        BigDecimal movementCost = unitCost != null ? unitCost : balance.getAverageUnitCost();
        if (unitCost != null) balance.receive(delta, unitCost); else balance.apply(delta);
        InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null, type, delta,
                reason, request.getIdempotencyKey(), fingerprint, context.getMembership().getId(), null, movementCost);
        inventory.persist(movement);
        inventory.flush();
        return FnbInventoryMapper.movement(movement, showCost(context));
    }

    @Transactional
    public FnbStockCountResponse stockCount(TenantPrincipal principal, UUID outletId, FnbStockCountRequest request) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        UUID tenantId = principal.getTenantId();
        List<FnbStockCountLineRequest> lines = request.getLines().stream()
                .sorted(Comparator.comparing(FnbStockCountLineRequest::getInventoryItemId)).toList();
        if (new HashSet<>(lines.stream().map(FnbStockCountLineRequest::getInventoryItemId).toList()).size() != lines.size())
            throw new InvalidOperationException("Bahan duplikat dalam stok opname.");
        String note = request.getNote() == null ? "" : request.getNote().trim();
        String fingerprint = tokens.digest(lines.stream().map(line -> line.getInventoryItemId() + "="
                + line.getCountedQuantity().stripTrailingZeros().toPlainString()).collect(Collectors.joining(";"))
                + "|" + note);
        String reason = note.isEmpty() ? "Stok opname" : "Stok opname: " + note;
        List<InventoryItemEntity> items = lines.stream()
                .map(line -> item(tenantId, outletId, line.getInventoryItemId())).toList();
        Map<UUID, InventoryMovementEntity> existing = new HashMap<>();
        for (FnbStockCountLineRequest line : lines) {
            InventoryMovementEntity movement = inventory.movementByKey(tenantId, key(request, line)).orElse(null);
            if (movement == null) continue;
            if (!movement.getRequestFingerprint().equals(fingerprint))
                throw new OperationConflictException("Kunci idempotensi telah digunakan.");
            existing.put(line.getInventoryItemId(), movement);
        }
        boolean replay = !existing.isEmpty();
        List<FnbStockCountLineResponse> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            FnbStockCountLineRequest line = lines.get(i);
            InventoryItemEntity item = items.get(i);
            BigDecimal counted = line.getCountedQuantity();
            InventoryMovementEntity movement = existing.get(item.getId());
            BigDecimal delta = movement == null ? BigDecimal.ZERO : movement.getQuantityDelta();
            BigDecimal previous = counted.subtract(delta);
            if (!replay) {
                InventoryBalanceEntity balance = lockedBalance(tenantId, outletId, item);
                previous = balance.getQuantityOnHand();
                delta = counted.subtract(previous);
                if (delta.signum() != 0) {
                    movement = InventoryMovementEntity.record(tenantId, outletId, item, null,
                            InventoryMovementEntity.Type.ADJUSTMENT, delta, reason, key(request, line), fingerprint,
                            context.getMembership().getId(), null, balance.getAverageUnitCost());
                    balance.apply(delta);
                    inventory.persist(movement);
                }
            }
            FnbStockCountLineResponse response = new FnbStockCountLineResponse();
            response.setInventoryItemId(item.getId());
            response.setName(item.getName());
            response.setUnit(item.getUnit());
            response.setPreviousQuantity(previous);
            response.setCountedQuantity(counted);
            response.setDifference(delta);
            response.setMovementId(movement == null ? null : movement.getId());
            result.add(response);
        }
        inventory.flush();
        FnbStockCountResponse response = new FnbStockCountResponse();
        response.setLines(result);
        return response;
    }

    @Transactional(readOnly = true)
    public List<FnbStockMovementResponse> movements(TenantPrincipal principal, UUID outletId, UUID itemId, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        boolean showCost = showCost(guard.requireOutlet(principal, outletId));
        item(principal.getTenantId(), outletId, itemId);
        return inventory.movements(principal.getTenantId(), outletId, itemId, page, size).stream()
                .map(movement -> FnbInventoryMapper.movement(movement, showCost)).toList();
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

    // The price suffix is added only when present so keys recorded before unit cost existed still replay.
    private String fingerprint(InventoryMovementEntity.Type type, UUID itemId, BigDecimal delta, String reason, BigDecimal unitCost) {
        String base = type + "|" + itemId + "|" + delta.stripTrailingZeros().toPlainString() + "|" + reason;
        return tokens.digest(unitCost == null ? base : base + "|" + unitCost.stripTrailingZeros().toPlainString());
    }

    private String key(FnbStockCountRequest request, FnbStockCountLineRequest line) {
        return "count:" + tokens.digest(request.getIdempotencyKey() + "|" + line.getInventoryItemId());
    }

    private boolean showCost(MerchantAccessContext context) {
        return context.getMembership().getRole() == Role.OWNERMERCHANT;
    }

    private InventoryBalanceEntity lockedBalance(UUID tenantId, UUID outletId, InventoryItemEntity item) {
        InventoryBalanceEntity balance = inventory.balance(tenantId, outletId, item.getId()).orElse(null);
        if (balance == null) {
            balance = InventoryBalanceEntity.create(tenantId, outletId, item);
            inventory.persist(balance);
        } else {
            inventory.lock(balance);
        }
        return balance;
    }
}
