package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.FnbInventoryMapper;
import org.lite.project.cashiersaas.dto.request.FnbInventoryItemRequest;
import org.lite.project.cashiersaas.dto.request.FnbPurchaseLineRequest;
import org.lite.project.cashiersaas.dto.request.FnbPurchaseRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockCountLineRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockCountRequest;
import org.lite.project.cashiersaas.dto.request.FnbStockMovementRequest;
import org.lite.project.cashiersaas.dto.response.FnbInventoryItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbPurchaseResponse;
import org.lite.project.cashiersaas.dto.response.FnbPurchaseSummaryResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountLineResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockCountResponse;
import org.lite.project.cashiersaas.dto.response.FnbStockMovementResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryItemEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryMovementEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.PurchaseEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.PurchaseLineEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.StockCountLineEntity;
import org.lite.project.cashiersaas.enums.Permission;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FnbInventoryService {
    private final MerchantAccessGuard guard;
    private final FnbInventoryRepository inventory;
    private final DigestService tokens;
    private final FnbMenuMarginService margins;
    private final AuditLogRepository audit;

    @Transactional
    public FnbInventoryItemResponse createItem(TenantPrincipal principal, UUID outletId, FnbInventoryItemRequest request) {
        MerchantAccessContext context = guard.require(principal, outletId, Permission.INVENTORY_ITEM_CREATE);
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
        MerchantAccessContext context = guard.require(principal, outletId,
                type == InventoryMovementEntity.Type.ADJUSTMENT ? Permission.STOCK_ADJUST : Permission.STOCK_MOVE);
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
            return FnbInventoryMapper.movement(existing, showCost(context), actorName(tenantId, existing));
        }
        InventoryBalanceEntity balance = lockedBalance(tenantId, outletId, item);
        BigDecimal averageBefore = balance.getAverageUnitCost();
        BigDecimal movementCost = unitCost != null ? unitCost : averageBefore;
        if (unitCost != null) balance.receive(delta, unitCost); else balance.apply(delta);
        InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null, type, delta,
                reason, request.getIdempotencyKey(), fingerprint, context.getMembership().getId(), null, movementCost);
        inventory.persist(movement);
        inventory.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                context.getMembership().getUser(), AuditLogEntity.Action.FNB_STOCK_MOVEMENT_RECORDED, movement.getId(), reason,
                "type=" + type + " item=" + itemId + " quantity=" + delta.toPlainString()
                        + " unitCost=" + (unitCost == null ? "-" : unitCost.toPlainString())));
        FnbStockMovementResponse response = FnbInventoryMapper.movement(movement, showCost(context), actorName(tenantId, movement));
        // Cost data is owner-only; only a restock priced above can move the average.
        if (unitCost != null && showCost(context)
                && (averageBefore == null || averageBefore.compareTo(balance.getAverageUnitCost()) != 0))
            response.setMarginWarnings(margins.restockWarnings(tenantId, outletId, itemId, context.getTenant().getTargetMarginPct()));
        return response;
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
        StockCountEntity recorded = inventory.stockCountByKey(tenantId, request.getIdempotencyKey()).orElse(null);
        if (recorded != null) {
            if (!recorded.getOutletId().equals(outletId) || !recorded.getRequestFingerprint().equals(fingerprint))
                throw new OperationConflictException("Kunci idempotensi telah digunakan.");
            FnbStockCountResponse replayed = new FnbStockCountResponse();
            replayed.setStockCountId(recorded.getId());
            // The query orders by name (leakage report); a replay must match the first response, ordered by item id.
            replayed.setLines(inventory.stockCountLines(tenantId, recorded.getId()).stream().map(FnbInventoryMapper::stockCountLine)
                    .sorted(Comparator.comparing(FnbStockCountLineResponse::getInventoryItemId)).toList());
            return replayed;
        }
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
        boolean replay = !existing.isEmpty(); // a count recorded before stock count headers existed
        Map<UUID, InventoryBalanceEntity> balances = new HashMap<>();
        StockCountEntity header = null;
        if (!replay) {
            // Every balance is locked before counted_at is taken, so a concurrent sale is either already in the
            // previous quantity (recorded before counted_at) or recorded after it.
            for (InventoryItemEntity item : items) balances.put(item.getId(), lockedBalance(tenantId, outletId, item));
            header = StockCountEntity.record(tenantId, outletId, note.isEmpty() ? null : note, request.getIdempotencyKey(),
                    fingerprint, context.getMembership().getId());
            inventory.persist(header);
        }
        List<FnbStockCountLineResponse> result = new ArrayList<>();
        int changed = 0;
        for (int i = 0; i < lines.size(); i++) {
            FnbStockCountLineRequest line = lines.get(i);
            InventoryItemEntity item = items.get(i);
            BigDecimal counted = line.getCountedQuantity();
            InventoryMovementEntity movement = existing.get(item.getId());
            BigDecimal delta = movement == null ? BigDecimal.ZERO : movement.getQuantityDelta();
            BigDecimal previous = counted.subtract(delta);
            if (!replay) {
                InventoryBalanceEntity balance = balances.get(item.getId());
                previous = balance.getQuantityOnHand();
                delta = counted.subtract(previous);
                if (delta.signum() != 0) {
                    changed++;
                    movement = InventoryMovementEntity.record(tenantId, outletId, item, null,
                            InventoryMovementEntity.Type.ADJUSTMENT, delta, reason, key(request, line), fingerprint,
                            context.getMembership().getId(), null, balance.getAverageUnitCost());
                    balance.apply(delta);
                    inventory.persist(movement);
                }
                inventory.persist(StockCountLineEntity.record(tenantId, header, item, previous, counted, delta,
                        balance.getAverageUnitCost(), movement == null ? null : movement.getId()));
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
        if (header != null)
            audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(),
                    context.getMembership().getUser(), AuditLogEntity.Action.FNB_STOCK_COUNT_RECORDED, header.getId(),
                    note.isEmpty() ? null : note, "lines=" + lines.size() + " changed=" + changed));
        FnbStockCountResponse response = new FnbStockCountResponse();
        response.setStockCountId(header == null ? null : header.getId());
        response.setLines(result);
        return response;
    }

    @Transactional
    public FnbPurchaseResponse purchase(TenantPrincipal principal, UUID outletId, FnbPurchaseRequest request) {
        MerchantAccessContext context = guard.require(principal, outletId, Permission.PURCHASE_RECORD);
        UUID tenantId = principal.getTenantId();
        if (request.getPurchasedOn().isAfter(LocalDate.now(ZoneId.of(context.getTenant().getTimeZone()))))
            throw new InvalidOperationException("Tanggal pembelian tidak boleh di masa depan.");
        List<FnbPurchaseLineRequest> lines = request.getLines().stream()
                .sorted(Comparator.comparing(FnbPurchaseLineRequest::getInventoryItemId)).toList();
        if (new HashSet<>(lines.stream().map(FnbPurchaseLineRequest::getInventoryItemId).toList()).size() != lines.size())
            throw new InvalidOperationException("Bahan duplikat dalam pembelian.");
        String supplier = request.getSupplierName() == null || request.getSupplierName().isBlank() ? null : request.getSupplierName().trim();
        String note = request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim();
        String fingerprint = tokens.digest(lines.stream().map(line -> line.getInventoryItemId() + "="
                + line.getQuantity().stripTrailingZeros().toPlainString() + "@" + line.getUnitCost().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(";")) + "|" + request.getPurchasedOn() + "|" + (supplier == null ? "" : supplier)
                + "|" + (note == null ? "" : note));
        PurchaseEntity recorded = inventory.purchaseByKey(tenantId, request.getIdempotencyKey()).orElse(null);
        if (recorded != null) {
            if (!recorded.getOutletId().equals(outletId) || !recorded.getRequestFingerprint().equals(fingerprint))
                throw new OperationConflictException("Kunci idempotensi telah digunakan.");
            return purchaseResponse(tenantId, recorded);
        }
        List<InventoryItemEntity> items = lines.stream().map(line -> item(tenantId, outletId, line.getInventoryItemId())).toList();
        List<InventoryBalanceEntity> balances = items.stream().map(item -> lockedBalance(tenantId, outletId, item)).toList();
        List<BigDecimal> lineTotals = lines.stream()
                .map(line -> line.getQuantity().multiply(line.getUnitCost()).setScale(2, RoundingMode.HALF_UP)).toList();
        PurchaseEntity header = PurchaseEntity.record(tenantId, outletId, supplier, request.getPurchasedOn(), note,
                lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add), request.getIdempotencyKey(), fingerprint,
                context.getMembership().getId());
        inventory.persist(header);
        String reason = supplier == null ? "Pembelian" : "Pembelian: " + supplier;
        List<PurchaseLineEntity> saved = new ArrayList<>();
        List<UUID> changedItems = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            FnbPurchaseLineRequest line = lines.get(i);
            InventoryItemEntity item = items.get(i);
            InventoryBalanceEntity balance = balances.get(i);
            BigDecimal averageBefore = balance.getAverageUnitCost();
            balance.receive(line.getQuantity(), line.getUnitCost());
            if (averageBefore == null || averageBefore.compareTo(balance.getAverageUnitCost()) != 0) changedItems.add(item.getId());
            // The key is hashed so a 128-char purchase key still fits the 128-char movement key column.
            InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null,
                    InventoryMovementEntity.Type.RESTOCK, line.getQuantity(), reason,
                    "purchase:" + tokens.digest(request.getIdempotencyKey()) + ":" + item.getId(), fingerprint,
                    context.getMembership().getId(), null, line.getUnitCost());
            inventory.persist(movement);
            PurchaseLineEntity saving = PurchaseLineEntity.record(tenantId, header, item, line.getQuantity(), line.getUnitCost(),
                    lineTotals.get(i), movement.getId());
            inventory.persist(saving);
            saved.add(saving);
        }
        inventory.flush();
        audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(),
                AuditLogEntity.Action.FNB_PURCHASE_RECORDED, header.getId(), note,
                "supplier=" + (supplier == null ? "-" : supplier) + " lines=" + lines.size() + " total=" + header.getTotal().toPlainString()));
        FnbPurchaseResponse response = FnbInventoryMapper.purchase(header, saved.stream()
                .sorted(Comparator.comparing((PurchaseLineEntity l) -> l.getInventoryItem().getName())
                        .thenComparing(l -> l.getInventoryItem().getId())).toList(),
                actorName(tenantId, header.getActorMembershipId()));
        if (showCost(context))
            response.setMarginWarnings(changedItems.stream().flatMap(id -> margins.restockWarnings(tenantId, outletId, id,
                    context.getTenant().getTargetMarginPct()).stream()).distinct().toList());
        return response;
    }

    @Transactional(readOnly = true)
    public FnbPurchaseResponse purchase(TenantPrincipal principal, UUID outletId, UUID id) {
        guard.require(principal, outletId, Permission.PURCHASE_RECORD);
        return purchaseResponse(principal.getTenantId(), inventory.purchase(principal.getTenantId(), outletId, id)
                .orElseThrow(ResourceNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<FnbPurchaseSummaryResponse> purchases(TenantPrincipal principal, UUID outletId, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        guard.require(principal, outletId, Permission.PURCHASE_RECORD);
        UUID tenantId = principal.getTenantId();
        List<PurchaseEntity> purchases = inventory.purchases(tenantId, outletId, page, size);
        Map<UUID, Integer> counts = inventory.purchaseLineCounts(tenantId, purchases.stream().map(PurchaseEntity::getId).toList());
        Map<UUID, String> names = inventory.memberNames(tenantId, purchases.stream()
                .map(PurchaseEntity::getActorMembershipId).distinct().toList());
        return purchases.stream().map(purchase -> FnbInventoryMapper.purchaseSummary(purchase,
                counts.getOrDefault(purchase.getId(), 0), names.get(purchase.getActorMembershipId()))).toList();
    }

    private FnbPurchaseResponse purchaseResponse(UUID tenantId, PurchaseEntity purchase) {
        return FnbInventoryMapper.purchase(purchase, inventory.purchaseLines(tenantId, purchase.getId()),
                actorName(tenantId, purchase.getActorMembershipId()));
    }

    @Transactional(readOnly = true)
    public List<FnbStockMovementResponse> movements(TenantPrincipal principal, UUID outletId, UUID itemId, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50)
            throw new InvalidOperationException("Parameter halaman tidak valid.");
        boolean showCost = showCost(guard.requireOutlet(principal, outletId));
        item(principal.getTenantId(), outletId, itemId);
        List<InventoryMovementEntity> movements = inventory.movements(principal.getTenantId(), outletId, itemId, page, size);
        Map<UUID, String> names = inventory.memberNames(principal.getTenantId(), movements.stream()
                .map(InventoryMovementEntity::getActorMembershipId).filter(Objects::nonNull).distinct().toList());
        return movements.stream().map(movement -> FnbInventoryMapper.movement(movement, showCost,
                names.get(movement.getActorMembershipId()))).toList();
    }

    private String actorName(UUID tenantId, InventoryMovementEntity movement) {
        return actorName(tenantId, movement.getActorMembershipId());
    }

    private String actorName(UUID tenantId, UUID membershipId) {
        if (membershipId == null) return null;
        return inventory.memberNames(tenantId, List.of(membershipId)).get(membershipId);
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
