package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.MerchantAuditEntryResponse;
import org.lite.project.cashiersaas.repository.MerchantAuditRepository;

import java.time.ZoneId;

public final class MerchantAuditMapper {
    private MerchantAuditMapper() {
    }

    public static MerchantAuditEntryResponse entry(MerchantAuditRepository.AuditRow row, ZoneId outletZone) {
        MerchantAuditEntryResponse response = new MerchantAuditEntryResponse();
        response.setId(row.id);
        response.setCreatedAt(row.createdAt);
        response.setLocalCreatedAt(row.createdAt.atZone(outletZone).toOffsetDateTime());
        response.setAction(row.action);
        response.setEntityType(row.entityType);
        response.setEntityId(row.entityId);
        response.setActorId(row.actorId);
        response.setActorDisplayName(row.actorDisplayName);
        response.setReason(row.reason);
        response.setDetails(row.details);
        return response;
    }
}
