package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.MemberSyncRequestedEvent;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.service.MemberSyncPublisher;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("unit")
class MemberSyncPublisherTest {
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MemberSyncPublisher publisher = new MemberSyncPublisher(events);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private TenantEntity tenant() {
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        ReflectionTestUtils.setField(tenant, "name", "Kopi Senja");
        return tenant;
    }

    private StaffUserEntity user(String phone) {
        StaffUserEntity user = StaffUserEntity.registerWithPassword("budi@example.com", "hash", "Budi Santoso", phone);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    @ParameterizedTest
    @CsvSource({"OWNERMERCHANT, ACTIVE", "CASHIER, DISABLED"})
    void publishesTheContractFieldsFromTheEntitiesAndCarriesTheStatus(Role role, String status) {
        publisher.memberChanged(tenant(), user("6281234567890"), role, status);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        MemberSyncRequestedEvent event = assertInstanceOf(MemberSyncRequestedEvent.class, captor.getValue());
        assertEquals(tenantId, event.getTenantId());
        assertEquals("Kopi Senja", event.getTenantName());
        assertEquals(userId, event.getUserId());
        assertEquals("Budi Santoso", event.getFullName());
        assertEquals("6281234567890", event.getPhoneNumber());
        assertEquals(role, event.getRole());
        assertEquals(status, event.getStatus());
    }

    @Test
    void usersWithoutAPhoneAreSkipped() {
        publisher.memberChanged(tenant(), user(null), Role.CASHIER, "ACTIVE");
        publisher.memberChanged(tenant(), user("  "), Role.CASHIER, "ACTIVE");

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void eventToStringNeverExposesThePhoneOrTheName() {
        publisher.memberChanged(tenant(), user("6281234567890"), Role.CASHIER, "ACTIVE");
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());

        String text = captor.getValue().toString();

        assertFalse(text.contains("6281234567890"), text);
        assertFalse(text.contains("Budi"), text);
        assertTrue(text.contains(userId.toString()));
    }
}
