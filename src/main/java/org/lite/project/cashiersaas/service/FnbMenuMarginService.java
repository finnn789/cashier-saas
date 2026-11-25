package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginReportResponse;
import org.lite.project.cashiersaas.dto.response.FnbMenuMarginResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.InventoryBalanceEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.repository.FnbInventoryRepository;
import org.lite.project.cashiersaas.repository.OutletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * HPP and margin per menu (PRD v3 FR-CS-01/02), always computed live from the recipe and the current average ingredient cost.
 * Options are not part of the HPP. The REST entry point authorises the owner and the PRO plan; {@link #outletMargins}
 * and {@link #restockWarnings} expect an authorised caller.
 */
@Service
@RequiredArgsConstructor
public class FnbMenuMarginService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PRICE_STEP = BigDecimal.valueOf(500);
    private static final Locale INDONESIA = Locale.forLanguageTag("id-ID");
    private static final Comparator<FnbMenuMarginResponse> ORDER = Comparator
            .comparing((FnbMenuMarginResponse menu) -> Status.valueOf(menu.getStatus()))
            .thenComparing(FnbMenuMarginService::belowTargetMargin, Comparator.nullsFirst(Comparator.<BigDecimal>naturalOrder()))
            .thenComparing(FnbMenuMarginResponse::getName);

    private enum Status { BELOW_TARGET, COST_UNKNOWN, NO_RECIPE, OK }

    private final MerchantAccessGuard guard;
    private final PlanEntitlementService planEntitlementService;
    private final FnbCatalogRepository catalog;
    private final FnbInventoryRepository inventory;
    private final OutletRepository outlets;

    @Transactional(readOnly = true)
    public FnbMenuMarginReportResponse menuMargins(TenantPrincipal principal, UUID outletId, boolean belowTargetOnly) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        planEntitlementService.require(principal.getTenantId(), PlanCode.PRO);
        FnbMenuMarginReportResponse report = report(principal.getTenantId(), context.getOutlet());
        if (belowTargetOnly) report.setMenus(report.getMenus().stream().filter(FnbMenuMarginService::isBelowTarget).toList());
        return report;
    }

    /** All non-archived menus of the outlet; the caller has already authorised tenant and outlet. 404 for a foreign outlet. */
    @Transactional(readOnly = true)
    public FnbMenuMarginReportResponse outletMargins(UUID tenantId, UUID outletId) {
        return report(tenantId, outlets.findInTenant(tenantId, outletId).orElseThrow(ResourceNotFoundException::new));
    }

    /** Indonesian warnings for the menus of the outlet that use the item and are below target right now; runs in the caller's transaction. */
    public List<String> restockWarnings(UUID tenantId, UUID outletId, UUID inventoryItemId, BigDecimal target) {
        return margins(tenantId, outletId, target, inventoryItemId).stream().filter(FnbMenuMarginService::isBelowTarget)
                .map(menu -> String.format(INDONESIA, "Margin %s turun ke %,.2f%% (target %,.2f%%), saran harga Rp%,.0f",
                        menu.getName(), menu.getMarginPct(), target, menu.getSuggestedPrice())).toList();
    }

    private FnbMenuMarginReportResponse report(UUID tenantId, OutletEntity outlet) {
        BigDecimal target = outlet.getTenant().getTargetMarginPct();
        List<FnbMenuMarginResponse> menus = margins(tenantId, outlet.getId(), target, null);
        FnbMenuMarginReportResponse report = new FnbMenuMarginReportResponse();
        report.setOutletId(outlet.getId());
        report.setOutletName(outlet.getName());
        report.setTargetMarginPct(target);
        report.setBelowTargetCount((int) menus.stream().filter(FnbMenuMarginService::isBelowTarget).count());
        report.setMenus(menus);
        return report;
    }

    // usingItemId null = every menu; otherwise only menus whose recipe uses that ingredient.
    private List<FnbMenuMarginResponse> margins(UUID tenantId, UUID outletId, BigDecimal target, UUID usingItemId) {
        List<ProductEntity> products = catalog.products(tenantId, outletId);
        Map<UUID, List<RecipeLineEntity>> recipes = new HashMap<>();
        for (RecipeLineEntity line : inventory.productRecipes(tenantId, outletId, products.stream().map(ProductEntity::getId).toList()))
            recipes.computeIfAbsent(line.getProduct().getId(), key -> new ArrayList<>()).add(line);
        List<UUID> itemIds = recipes.values().stream().flatMap(List::stream).map(line -> line.getInventoryItem().getId()).distinct().toList();
        Map<UUID, InventoryBalanceEntity> balances = new HashMap<>();
        for (InventoryBalanceEntity balance : inventory.balances(tenantId, outletId, itemIds))
            balances.put(balance.getInventoryItem().getId(), balance);

        List<FnbMenuMarginResponse> result = new ArrayList<>();
        for (ProductEntity product : products) {
            List<RecipeLineEntity> lines = recipes.getOrDefault(product.getId(), List.of());
            if (usingItemId != null && lines.stream().noneMatch(line -> line.getInventoryItem().getId().equals(usingItemId))) continue;
            result.add(margin(product, lines, balances, target));
        }
        result.sort(ORDER);
        return result;
    }

    private FnbMenuMarginResponse margin(ProductEntity product, List<RecipeLineEntity> lines,
                                         Map<UUID, InventoryBalanceEntity> balances, BigDecimal target) {
        FnbMenuMarginResponse response = new FnbMenuMarginResponse();
        response.setProductId(product.getId());
        response.setName(product.getName());
        response.setCategoryName(product.getCategory().getName());
        response.setPrice(product.getPrice());
        List<RecipeLineEntity> active = lines.stream().filter(line -> line.getInventoryItem().isActive()).toList();
        if (active.isEmpty()) {
            response.setStatus(Status.NO_RECIPE.name());
            return response;
        }
        List<String> missing = active.stream().filter(line -> {
            InventoryBalanceEntity balance = balances.get(line.getInventoryItem().getId());
            return balance == null || balance.getAverageUnitCost() == null;
        }).map(line -> line.getInventoryItem().getName()).distinct().sorted().toList();
        if (!missing.isEmpty()) {
            response.setStatus(Status.COST_UNKNOWN.name());
            response.setMissingCostItems(missing);
            return response;
        }
        BigDecimal cost = FnbStockService.itemCost(1, lines, balances);
        response.setHpp(cost.setScale(2, RoundingMode.HALF_UP));
        response.setStatus(Status.OK.name());
        if (product.getPrice().signum() == 0) return response; // margin is undefined for a free menu
        BigDecimal marginPct = product.getPrice().subtract(cost).multiply(HUNDRED).divide(product.getPrice(), 2, RoundingMode.HALF_UP);
        response.setMarginPct(marginPct);
        if (marginPct.compareTo(target) < 0) {
            response.setStatus(Status.BELOW_TARGET.name());
            response.setSuggestedPrice(suggestedPrice(cost, target));
        }
        return response;
    }

    // hpp / (1 - target/100), rounded up to the next Rp500; target < 100 is guaranteed by the settings validation and the CHECK.
    private BigDecimal suggestedPrice(BigDecimal cost, BigDecimal target) {
        return cost.multiply(HUNDRED).divide(HUNDRED.subtract(target), 4, RoundingMode.CEILING)
                .divide(PRICE_STEP, 0, RoundingMode.CEILING).multiply(PRICE_STEP).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean isBelowTarget(FnbMenuMarginResponse menu) {
        return Status.BELOW_TARGET.name().equals(menu.getStatus());
    }

    private static BigDecimal belowTargetMargin(FnbMenuMarginResponse menu) {
        return isBelowTarget(menu) ? menu.getMarginPct() : null;
    }
}
