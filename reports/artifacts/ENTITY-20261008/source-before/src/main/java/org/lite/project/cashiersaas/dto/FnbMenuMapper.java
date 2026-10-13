package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbTableResponse;
import org.lite.project.cashiersaas.dto.response.PublicMenuResponse;
import org.lite.project.cashiersaas.entity.fnb.*;
import java.util.List;

public final class FnbMenuMapper {
    private FnbMenuMapper() { }
    public static org.lite.project.cashiersaas.dto.response.FnbQrResponse qr(TableQrTokenEntity qr) {
        var response = new org.lite.project.cashiersaas.dto.response.FnbQrResponse();
        response.setQrType(qr.getQrType()); response.setToken(qr.getToken());
        response.setMenuPath("/api/v1/public/fnb/menu/" + qr.getToken());
        return response;
    }
    public static FnbTableResponse table(DiningTableEntity table, TableQrTokenEntity qr) {
        FnbTableResponse response = new FnbTableResponse();
        response.setId(table.getId()); response.setName(table.getName());
        response.setStatus(table.getStatus()); response.setActive(table.isActive());
        response.setQrToken(qr.getToken());
        return response;
    }
    public static PublicMenuResponse menu(TableQrTokenEntity qr, List<CategoryEntity> categories,
                                          List<ProductEntity> products, List<OptionGroupEntity> groups,
                                          List<ProductOptionEntity> options) {
        PublicMenuResponse response = new PublicMenuResponse();
        response.setOutletName(qr.getOutlet().getName());
        response.setTableName(qr.getDiningTable() == null ? null : qr.getDiningTable().getName());
        response.setQrType(qr.getQrType());
        response.setServiceTypes(qr.getDiningTable() == null
                ? List.of(org.lite.project.cashiersaas.enums.ServiceType.DINE_IN, org.lite.project.cashiersaas.enums.ServiceType.TAKEAWAY)
                : List.of(org.lite.project.cashiersaas.enums.ServiceType.DINE_IN));
        response.setTaxRate(qr.getTenant().getTaxRate());
        response.setServiceChargeRate(qr.getTenant().getServiceChargeRate());
        response.setCategories(categories.stream().map(category -> {
            PublicMenuResponse.Category c = new PublicMenuResponse.Category();
            c.setId(category.getId()); c.setName(category.getName());
            c.setProducts(products.stream().filter(p -> p.getCategory().getId().equals(category.getId()))
                    .map(product -> {
                        PublicMenuResponse.Product p = new PublicMenuResponse.Product();
                        p.setId(product.getId()); p.setName(product.getName()); p.setDescription(product.getDescription());
                        p.setPrice(product.getPrice()); p.setImageUrl(product.getImageUrl());
                        p.setAvailable(product.isAvailable());
                        p.setGroups(groups.stream().filter(g -> g.getProduct().getId().equals(product.getId()))
                                .map(group -> {
                                    PublicMenuResponse.OptionGroup g = new PublicMenuResponse.OptionGroup();
                                    g.setId(group.getId()); g.setName(group.getName()); g.setRequired(group.isRequired());
                                    g.setMinimumSelections(group.getMinimumSelections()); g.setMaximumSelections(group.getMaximumSelections());
                                    g.setOptions(options.stream().filter(o -> o.getOptionGroup().getId().equals(group.getId()))
                                            .map(option -> {
                                                PublicMenuResponse.Option o = new PublicMenuResponse.Option();
                                                o.setId(option.getId()); o.setName(option.getName());
                                                o.setPriceAdjustment(option.getPriceAdjustment()); o.setAvailable(option.isAvailable());
                                                return o;
                                            }).toList());
                                    return g;
                                }).toList());
                        return p;
                    }).toList());
            return c;
        }).toList());
        return response;
    }
}
