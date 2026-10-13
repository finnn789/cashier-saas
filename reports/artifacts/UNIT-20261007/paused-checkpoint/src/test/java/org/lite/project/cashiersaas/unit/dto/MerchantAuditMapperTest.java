package org.lite.project.cashiersaas.unit.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.dto.MerchantAuditMapper;
import org.lite.project.cashiersaas.dto.response.MerchantAuditEntryResponse;
import org.lite.project.cashiersaas.dto.response.MerchantAuditPageResponse;
import org.lite.project.cashiersaas.repository.MerchantAuditRepository;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MerchantAuditMapperTest {
    @Test
    void mapsProjectionIncludingActorFullNameReasonAndDetails() {
        UUID id = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-07T03:30:00Z");
        MerchantAuditRepository.AuditRow row = new MerchantAuditRepository.AuditRow(id, createdAt,
                "FNB_ORDER_CANCELLED", "SALES_ORDER", entityId, actorId, "Ayu Owner", "Duplicate order",
                "{\"source\":\"POS\"}");

        MerchantAuditEntryResponse response = MerchantAuditMapper.entry(row, ZoneId.of("Asia/Jakarta"));

        assertEquals(id, response.getId());
        assertEquals(createdAt, response.getCreatedAt());
        assertEquals("FNB_ORDER_CANCELLED", response.getAction());
        assertEquals("SALES_ORDER", response.getEntityType());
        assertEquals(entityId, response.getEntityId());
        assertEquals(actorId, response.getActorId());
        assertEquals("2026-10-07T10:30+07:00", response.getLocalCreatedAt().toString());
        assertEquals("Ayu Owner", response.getActorDisplayName());
        assertEquals("Duplicate order", response.getReason());
        assertEquals("{\"source\":\"POS\"}", response.getDetails());
    }

    @Test
    void nullOptionalProjectionFieldsAreAbsentFromJacksonJson() throws Exception {
        MerchantAuditRepository.AuditRow row = new MerchantAuditRepository.AuditRow(UUID.randomUUID(),
                Instant.parse("2026-10-07T03:30:00Z"), "ACTION", "ENTITY", UUID.randomUUID(), null, null, null, null);
        MerchantAuditEntryResponse response = MerchantAuditMapper.entry(row, ZoneId.of("UTC"));
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(response);

        assertTrue(json.contains("\"action\":\"ACTION\""));
        assertFalse(json.contains("actorId"));
        assertFalse(json.contains("actorDisplayName"));
        assertFalse(json.contains("reason"));
        assertFalse(json.contains("details"));
    }

    @Test
    void jacksonThreeOmitsNullFieldsInPageAndNestedEntriesButRetainsPrimitiveMetadata() {
        MerchantAuditRepository.AuditRow row = new MerchantAuditRepository.AuditRow(UUID.randomUUID(),
                Instant.parse("2026-10-07T03:30:00Z"), "ACTION", "ENTITY", UUID.randomUUID(), null, null, null, null);
        MerchantAuditPageResponse page = new MerchantAuditPageResponse();
        page.setEntries(List.of(MerchantAuditMapper.entry(row, ZoneId.of("UTC"))));
        ObjectMapper mapper = new ObjectMapper();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(page));
        JsonNode entry = json.path("entries").get(0);

        assertFalse(json.has("startDate"));
        assertFalse(json.has("endDate"));
        assertFalse(json.has("timeZone"));
        for (String field : List.of("page", "size", "total", "hasNext")) {
            assertTrue(json.has(field), field);
        }
        assertEquals(0, json.path("page").asInt());
        assertEquals(0, json.path("size").asInt());
        assertEquals(0L, json.path("total").asLong());
        assertFalse(json.path("hasNext").asBoolean());
        assertEquals(row.id.toString(), entry.path("id").asText());
        assertEquals("ACTION", entry.path("action").asText());
        assertTrue(entry.has("createdAt"));
        assertTrue(entry.has("localCreatedAt"));
        for (String field : List.of("actorId", "actorDisplayName", "reason", "details")) {
            assertFalse(entry.has(field), field);
        }
    }
}
