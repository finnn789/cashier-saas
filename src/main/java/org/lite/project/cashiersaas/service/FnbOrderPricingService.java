package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FnbOrderPricingService {
    private final FnbCatalogRepository catalog;

    public PricedOrder price(UUID tenantId, UUID outletId, List<LineInput> inputs,
                             BigDecimal taxRate, BigDecimal serviceChargeRate) {
        if (inputs == null || inputs.isEmpty()) throw new InvalidOperationException("Pesanan harus memiliki item.");
        requireRate(taxRate, "Tarif pajak tidak valid.");
        requireRate(serviceChargeRate, "Tarif biaya layanan tidak valid.");
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (LineInput input : inputs) {
            PricedLine line = priceLine(tenantId, outletId, input);
            lines.add(line);
            subtotal = subtotal.add(line.lineTotal());
        }
        BigDecimal taxAmount = percentage(subtotal, taxRate);
        BigDecimal serviceChargeAmount = percentage(subtotal, serviceChargeRate);
        return new PricedOrder(lines, subtotal, taxAmount, serviceChargeAmount,
                subtotal.add(taxAmount).add(serviceChargeAmount));
    }

    private PricedLine priceLine(UUID tenantId, UUID outletId, LineInput input) {
        if (input == null || input.productId() == null || input.quantity() < 1 || input.optionIds() == null)
            throw new InvalidOperationException("Item pesanan tidak valid.");
        ProductEntity product = catalog.product(tenantId, outletId, input.productId())
                .orElseThrow(() -> new InvalidOperationException("Produk tidak tersedia."));
        catalog.lock(product);
        catalog.lock(product.getCategory());
        if (!product.isAvailable() || product.isArchived() || product.getCategory().isArchived())
            throw new InvalidOperationException("Produk tidak tersedia.");
        Set<UUID> selectedIds = new HashSet<>(input.optionIds());
        if (selectedIds.size() != input.optionIds().size())
            throw new InvalidOperationException("Pilihan tidak boleh berulang.");
        List<ProductOptionEntity> selected = selectedOptions(tenantId, outletId, product, selectedIds);
        if (selected.size() != selectedIds.size())
            throw new InvalidOperationException("Pilihan bukan milik produk.");
        BigDecimal unitPrice = product.getPrice();
        for (ProductOptionEntity option : selected) unitPrice = unitPrice.add(option.getPriceAdjustment());
        if (unitPrice.signum() < 0) throw new InvalidOperationException("Harga efektif item tidak valid.");
        BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(input.quantity())).setScale(2, RoundingMode.HALF_UP);
        if (lineTotal.signum() < 0) throw new InvalidOperationException("Total item tidak valid.");
        return new PricedLine(input, product, selected, unitPrice, lineTotal);
    }

    private List<ProductOptionEntity> selectedOptions(UUID tenantId, UUID outletId, ProductEntity product,
                                                       Set<UUID> selectedIds) {
        List<ProductOptionEntity> selected = new ArrayList<>();
        for (OptionGroupEntity group : catalog.groups(tenantId, outletId, product.getId())) {
            catalog.lock(group);
            int selectedCount = 0;
            for (ProductOptionEntity option : catalog.options(tenantId, outletId, group.getId())) {
                if (selectedIds.contains(option.getId())) {
                    catalog.lock(option);
                    if (!option.isAvailable()) throw new InvalidOperationException("Pilihan tidak tersedia.");
                    selected.add(option);
                    selectedCount++;
                }
            }
            if (selectedCount < group.getMinimumSelections() || selectedCount > group.getMaximumSelections())
                throw new InvalidOperationException("Jumlah pilihan pada grup tidak valid.");
        }
        return selected;
    }

    private static void requireRate(BigDecimal rate, String message) {
        if (rate == null || rate.signum() < 0) throw new InvalidOperationException(message);
    }

    private static BigDecimal percentage(BigDecimal value, BigDecimal rate) {
        return value.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    public record LineInput(UUID productId, int quantity, String note, List<UUID> optionIds) { }

    public record PricedLine(LineInput input, ProductEntity product, List<ProductOptionEntity> options,
                             BigDecimal unitPrice, BigDecimal lineTotal) { }

    public record PricedOrder(List<PricedLine> lines, BigDecimal subtotal, BigDecimal taxAmount,
                              BigDecimal serviceChargeAmount, BigDecimal total) { }
}
