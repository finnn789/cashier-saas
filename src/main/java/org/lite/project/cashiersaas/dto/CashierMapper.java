package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.CashierCatalogItemResponse;
import org.lite.project.cashiersaas.dto.response.CashierSaleLineResponse;
import org.lite.project.cashiersaas.dto.response.CashierSaleResponse;
import org.lite.project.cashiersaas.dto.response.OutletResponse;
import org.lite.project.cashiersaas.entity.laundry.CashierCatalogItemEntity;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleEntity;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleItemEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;

import java.util.List;

public final class CashierMapper {
    private CashierMapper() {
    }

    public static OutletResponse toOutletResponse(OutletEntity outlet) {
        OutletResponse response = new OutletResponse();
        response.setId(outlet.getId());
        response.setName(outlet.getName());
        response.setTimeZone(outlet.getTimeZone());
        response.setAddress(outlet.getAddress());
        response.setPhoneNumber(outlet.getPhoneNumber());
        return response;
    }

    public static CashierCatalogItemResponse toCatalogResponse(CashierCatalogItemEntity item) {
        CashierCatalogItemResponse response = new CashierCatalogItemResponse();
        response.setId(item.getId());
        response.setName(item.getName());
        response.setBillingUnit(item.getBillingUnit());
        response.setUnitPrice(item.getUnitPrice());
        response.setActive(item.isActive());
        return response;
    }

    public static CashierSaleResponse toSaleResponse(CashierSaleEntity sale, List<CashierSaleItemEntity> lines) {
        CashierSaleResponse response = new CashierSaleResponse();
        response.setId(sale.getId());
        response.setOutletId(sale.getOutlet().getId());
        response.setSaleNumber(sale.getSaleNumber());
        response.setCustomerName(sale.getCustomerName());
        response.setTotal(sale.getTotalAmount());
        response.setCurrencyCode(sale.getCurrencyCode());
        response.setPaymentMethod(sale.getPaymentMethod());
        response.setPaymentStatus("PAID");
        response.setTenderedAmount(sale.getTenderedAmount());
        response.setChangeAmount(sale.getChangeAmount());
        response.setPaymentReference(sale.getPaymentReference());
        response.setPaidAt(sale.getPaidAt());
        response.setItems(lines.stream().map(CashierMapper::toLineResponse).toList());
        return response;
    }

    private static CashierSaleLineResponse toLineResponse(CashierSaleItemEntity line) {
        CashierSaleLineResponse response = new CashierSaleLineResponse();
        response.setCatalogItemId(line.getCatalogItem().getId());
        response.setName(line.getNameSnapshot());
        response.setBillingUnit(line.getBillingUnitSnapshot());
        response.setQuantity(line.getQuantity());
        response.setUnitPrice(line.getUnitPriceSnapshot());
        response.setLineTotal(line.getLineTotalAmount());
        return response;
    }
}
