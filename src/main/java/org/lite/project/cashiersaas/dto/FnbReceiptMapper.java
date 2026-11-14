package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.FnbReceiptItemResponse;
import org.lite.project.cashiersaas.dto.response.FnbReceiptOptionResponse;
import org.lite.project.cashiersaas.dto.response.FnbReceiptResponse;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.lite.project.cashiersaas.entity.fnb.PaymentRefundEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;

import java.time.ZoneId;
import java.util.List;

public final class FnbReceiptMapper {

    private FnbReceiptMapper() {
    }

    public static FnbReceiptResponse receipt(PaymentEntity payment, PaymentRefundEntity refund, List<OrderItemEntity> items,
                                             List<OrderItemOptionEntity> options) {
        SalesOrderEntity order = payment.getSalesOrder();
        FnbReceiptResponse response = new FnbReceiptResponse();
        response.setReceiptNumber(payment.getReceiptNumber());
        response.setOrderNumber(order.getOrderNumber());
        response.setMerchantName(payment.getTenant().getName());
        response.setBusinessName(payment.getTenant().getName());
        response.setLogoUrl(payment.getTenant().getLogoUrl());
        response.setReceiptFooter(payment.getTenant().getReceiptFooter());
        response.setMethodLabel(payment.getMethodLabel());
        response.setCustomerLabel(order.getCustomerLabel());
        response.setOrderNote(order.getOrderNote());
        response.setOutletName(payment.getOutlet().getName());
        response.setOutletAddress(payment.getOutlet().getAddress());
        response.setOutletPhone(payment.getOutlet().getPhoneNumber());
        response.setCurrency(payment.getTenant().getCurrency());
        response.setTimeZone(payment.getOutlet().getTimeZone());
        response.setPaidAt(payment.getPaidAt().atZone(ZoneId.of(payment.getOutlet().getTimeZone())));
        response.setSubtotal(order.getSubtotal());
        response.setDiscountAmount(order.getDiscountAmount());
        response.setTaxAmount(order.getTaxAmount());
        response.setServiceChargeAmount(order.getServiceChargeAmount());
        response.setTotal(order.getTotal());
        response.setMethod(payment.getMethod());
        response.setTendered(payment.getAmountPaid());
        response.setChangeAmount(payment.getChangeAmount());
        response.setReceivedBy(payment.getReceivedBy().getFullName());
        if (refund != null) {
            response.setRefunded(true);
            response.setRefundedAt(refund.getRefundedAt().atZone(ZoneId.of(payment.getOutlet().getTimeZone())));
            response.setRefundReason(refund.getReason());
        }
        response.setItems(items.stream().map(item -> item(item, options)).toList());
        response.setHtml(html(response));
        return response;
    }

    private static FnbReceiptItemResponse item(OrderItemEntity entity, List<OrderItemOptionEntity> options) {
        FnbReceiptItemResponse value = new FnbReceiptItemResponse();
        value.setProductName(entity.getProductNameSnapshot());
        value.setQuantity(entity.getQuantity());
        value.setUnitPrice(entity.getUnitPriceSnapshot());
        value.setLineTotal(entity.getLineTotal());
        value.setOptions(options.stream()
                .filter(option -> option.getOrderItem().getId().equals(entity.getId()))
                .map(option -> option(option))
                .toList());
        return value;
    }

    private static FnbReceiptOptionResponse option(OrderItemOptionEntity entity) {
        FnbReceiptOptionResponse value = new FnbReceiptOptionResponse();
        value.setName(entity.getOptionNameSnapshot());
        value.setPriceAdjustment(entity.getPriceAdjustmentSnapshot());
        return value;
    }

    private static String html(FnbReceiptResponse receipt) {
        StringBuilder value = new StringBuilder("""
                <!doctype html><html><head><meta charset="utf-8"><style>
                @media print{body{margin:0}}body{font-family:Arial,sans-serif;max-width:360px;margin:auto;padding:16px}
                .right{text-align:right}.line{border-top:1px dashed #444;margin:8px 0}table{width:100%;border-collapse:collapse}td{vertical-align:top}
                </style></head><body>""");
        if (receipt.getLogoUrl() != null) {
            value.append("<div style=\"text-align:center\"><img src=\"").append(escape(receipt.getLogoUrl()))
                    .append("\" alt=\"\" style=\"max-height:64px;max-width:100%\"></div>");
        }
        value.append("<h2>").append(escape(receipt.getBusinessName())).append("</h2><div>");
        value.append(escape(receipt.getOutletName())).append("<br>");
        value.append(escape(receipt.getOutletAddress())).append("<br>");
        value.append(escape(receipt.getOutletPhone())).append("</div><div class=\"line\"></div><div>");
        if (receipt.isRefunded()) {
            value.append("<div style=\"text-align:center;border:2px solid #b00;color:#b00;font-weight:bold;padding:4px\">REFUND</div>");
        }
        appendHtmlLine(value, "Struk", receipt.getReceiptNumber());
        appendHtmlLine(value, "Pesanan", receipt.getOrderNumber());
        appendHtmlLine(value, "Dibayar", receipt.getPaidAt());
        appendHtmlLine(value, "Zona waktu", receipt.getTimeZone());
        if (receipt.getCustomerLabel() != null) appendHtmlLine(value, "Pelanggan", receipt.getCustomerLabel());
        if (receipt.getOrderNote() != null) appendHtmlLine(value, "Catatan", receipt.getOrderNote());
        value.append("</div><div class=\"line\"></div><table>");
        for (FnbReceiptItemResponse item : receipt.getItems()) {
            value.append("<tr><td>").append(item.getQuantity()).append(" x ")
                    .append(escape(item.getProductName())).append(" @ ")
                    .append(escape(item.getUnitPrice())).append("</td><td class=\"right\">")
                    .append(escape(item.getLineTotal())).append("</td></tr>");
            for (FnbReceiptOptionResponse option : item.getOptions()) {
                value.append("<tr><td>&nbsp;&nbsp;+ ").append(escape(option.getName()))
                        .append("</td><td class=\"right\">").append(escape(option.getPriceAdjustment()))
                        .append("</td></tr>");
            }
        }
        value.append("</table><div class=\"line\"></div><table>");
        appendMoneyRow(value, "Subtotal", receipt.getSubtotal());
        appendMoneyRow(value, "Diskon", receipt.getDiscountAmount());
        appendMoneyRow(value, "Pajak", receipt.getTaxAmount());
        appendMoneyRow(value, "Layanan", receipt.getServiceChargeAmount());
        value.append("<tr><td><b>Total</b></td><td class=\"right\"><b>")
                .append(escape(receipt.getTotal())).append(" ").append(escape(receipt.getCurrency()))
                .append("</b></td></tr>");
        appendMoneyRow(value, "Metode", receipt.getMethodLabel() == null ? receipt.getMethod()
                : receipt.getMethod() + " - " + receipt.getMethodLabel());
        appendMoneyRow(value, "Diterima", receipt.getTendered());
        appendMoneyRow(value, "Kembalian", receipt.getChangeAmount());
        value.append("</table><div class=\"line\"></div>");
        appendHtmlLine(value, "Kasir", receipt.getReceivedBy());
        if (receipt.isRefunded()) {
            appendHtmlLine(value, "Direfund", receipt.getRefundedAt());
            appendHtmlLine(value, "Alasan refund", receipt.getRefundReason());
        }
        if (receipt.getReceiptFooter() != null) {
            value.append("<div class=\"line\"></div><div style=\"text-align:center\">")
                    .append(escape(receipt.getReceiptFooter())).append("</div>");
        }
        return value.append("</body></html>").toString();
    }

    private static void appendMoneyRow(StringBuilder value, String label, Object amount) {
        value.append("<tr><td>").append(escape(label)).append("</td><td class=\"right\">")
                .append(escape(amount)).append("</td></tr>");
    }

    private static void appendLine(StringBuilder value, String label, Object content) {
        value.append(escape(label)).append(": ").append(escape(content)).append("<br>");
    }

    private static void appendHtmlLine(StringBuilder value, String label, Object content) {
        appendLine(value, label, content);
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
