package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.MerchantBusinessSettingsMapper;
import org.lite.project.cashiersaas.dto.request.MerchantBusinessSettingsRequest;
import org.lite.project.cashiersaas.dto.response.MerchantBusinessSettingsResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.MerchantBusinessSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantBusinessSettingsService {
    private final MerchantAccessGuard guard;
    private final FnbOrderRepository orders;
    private final MerchantBusinessSettingsRepository settings;
    private final AuditLogRepository auditLogs;

    @Transactional(readOnly = true)
    public MerchantBusinessSettingsResponse get(TenantPrincipal principal, UUID outletId) {
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        return MerchantBusinessSettingsMapper.response(context.getTenant(), context.getOutlet());
    }

    @Transactional
    public MerchantBusinessSettingsResponse update(TenantPrincipal principal, UUID outletId,
                                                   MerchantBusinessSettingsRequest request) {
        orders.lockTenant(principal.getTenantId());
        MerchantAccessContext context = guard.requireOwner(principal, outletId);
        Values values = normalize(request);
        TenantEntity tenant = context.getTenant();
        OutletEntity outlet = context.getOutlet();
        String before = details(tenant, outlet);

        if (!tenant.getCurrency().equals(values.currency()) && settings.hasCurrencyUse(tenant.getId())) {
            throw new OperationConflictException("Mata uang tidak dapat diubah setelah tenant memiliki pesanan atau penjualan.");
        }

        boolean tenantChanged = tenant.updateBusinessSettings(values.businessName(), values.logoUrl(), values.currency(),
                values.address(), values.phoneNumber(), values.taxRate(), values.serviceChargeRate(), values.timeZone());
        boolean outletChanged = outlet.updateBusinessSettings(values.outletName(), values.timeZone(), values.outletAddress(),
                values.outletPhoneNumber());
        boolean footerChanged = tenant.changeReceiptFooter(values.receiptFooter());
        if (tenantChanged || outletChanged || footerChanged) {
            auditLogs.persist(AuditLogEntity.recordAction(tenant, outlet, context.getMembership().getUser(),
                    AuditLogEntity.Action.BUSINESS_SETTINGS_CHANGED, outlet.getId(), null,
                    "{\"before\":" + before + ",\"after\":" + details(tenant, outlet) + "}"));
        }
        return MerchantBusinessSettingsMapper.response(tenant, outlet);
    }

    private Values normalize(MerchantBusinessSettingsRequest request) {
        String currency = request.getCurrency().trim();
        validateCurrency(currency);
        String timeZone = request.getTimeZone().trim();
        validateTimeZone(timeZone);
        String logoUrl = optional(request.getLogoUrl());
        validateLogoUrl(logoUrl);
        return new Values(requiredName(request.getBusinessName(), "Nama bisnis", 120), logoUrl, currency,
                optional(request.getAddress()), optional(request.getPhoneNumber()), rate(request.getTaxRate()),
                rate(request.getServiceChargeRate()), requiredName(request.getOutletName(), "Nama outlet", 120),
                optional(request.getOutletAddress()), optional(request.getOutletPhoneNumber()), timeZone, optional(request.getReceiptFooter()));
    }

    private void validateCurrency(String currency) {
        try {
            if (Currency.getInstance(currency).getDefaultFractionDigits() != 2) {
                throw new InvalidOperationException("Mata uang harus memiliki dua digit pecahan.");
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationException("Kode mata uang ISO-4217 tidak valid.");
        }
    }

    private void validateTimeZone(String timeZone) {
        try {
            ZoneId.of(timeZone);
        } catch (RuntimeException exception) {
            throw new InvalidOperationException("Zona waktu tidak valid.");
        }
    }

    private void validateLogoUrl(String logoUrl) {
        if (logoUrl == null) return;
        try {
            URI uri = URI.create(logoUrl);
            int port = uri.getPort();
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || (port != -1 && (port < 1 || port > 65535))) {
                throw new InvalidOperationException("URL logo harus berupa URL HTTPS absolut.");
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidOperationException("URL logo harus berupa URL HTTPS absolut.");
        }
    }

    private BigDecimal rate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0
                || rate.scale() > 2) {
            throw new InvalidOperationException("Tarif harus antara 0 dan 100 dengan maksimal dua angka desimal.");
        }
        try {
            return rate.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidOperationException("Tarif harus antara 0 dan 100 dengan maksimal dua angka desimal.");
        }
    }

    private String requiredName(String value, String label, int maximumLength) {
        if (value == null) throw new InvalidOperationException(label + " wajib diisi.");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new InvalidOperationException(label + " wajib diisi dan tidak boleh melebihi " + maximumLength + " karakter.");
        }
        return normalized;
    }

    private String optional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String details(TenantEntity tenant, OutletEntity outlet) {
        return "{\"businessName\":\"" + escape(tenant.getName()) + "\",\"logoUrl\":" + nullable(auditLogoUrl(tenant.getLogoUrl()))
                + ",\"currency\":\"" + escape(tenant.getCurrency()) + "\",\"address\":" + nullable(tenant.getAddress())
                + ",\"phoneNumber\":" + nullable(tenant.getPhoneNumber()) + ",\"taxRate\":\"" + tenant.getTaxRate()
                + "\",\"serviceChargeRate\":\"" + tenant.getServiceChargeRate() + "\",\"outletName\":\""
                + escape(outlet.getName()) + "\",\"outletAddress\":" + nullable(outlet.getAddress())
                + ",\"outletPhoneNumber\":" + nullable(outlet.getPhoneNumber()) + ",\"timeZone\":\""
                + escape(outlet.getTimeZone()) + "\",\"receiptFooter\":" + nullable(tenant.getReceiptFooter()) + "}";
    }

    private String nullable(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private String auditLogoUrl(String logoUrl) {
        if (logoUrl == null) return null;
        try {
            return URI.create(logoUrl).getUserInfo() == null ? logoUrl : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x001F) {
                escaped.append("\\u");
                String hexadecimal = Integer.toHexString(character);
                for (int padding = hexadecimal.length(); padding < 4; padding++) escaped.append('0');
                escaped.append(hexadecimal);
            } else if (character == '\\' || character == '\"') {
                escaped.append('\\').append(character);
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private record Values(String businessName, String logoUrl, String currency, String address, String phoneNumber,
                          BigDecimal taxRate, BigDecimal serviceChargeRate, String outletName, String outletAddress,
                          String outletPhoneNumber, String timeZone, String receiptFooter) { }
}
