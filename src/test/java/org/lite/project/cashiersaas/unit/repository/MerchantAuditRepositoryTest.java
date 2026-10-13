package org.lite.project.cashiersaas.unit.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.RestrictionBuilder;
import com.blazebit.persistence.SelectObjectBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.repository.MerchantAuditRepository;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MerchantAuditRepositoryTest {
    @Mock private EntityManager entityManager;
    @Mock private CriteriaBuilderFactory criteriaBuilderFactory;
    @Mock(answer = RETURNS_SELF) private CriteriaBuilder<AuditLogEntity> query;
    @Mock(answer = RETURNS_SELF) private CriteriaBuilder<MerchantAuditRepository.AuditRow> projected;
    @Mock(answer = RETURNS_SELF)
    private SelectObjectBuilder<CriteriaBuilder<MerchantAuditRepository.AuditRow>> selection;
    @Mock private RestrictionBuilder<CriteriaBuilder<AuditLogEntity>> tenant;
    @Mock private RestrictionBuilder<CriteriaBuilder<AuditLogEntity>> outlet;
    @Mock private RestrictionBuilder<CriteriaBuilder<AuditLogEntity>> time;
    @Mock private RestrictionBuilder<CriteriaBuilder<AuditLogEntity>> action;
    @Mock private RestrictionBuilder<CriteriaBuilder<AuditLogEntity>> actor;
    @Mock private RestrictionBuilder<CriteriaBuilder<AuditLogEntity>> entity;
    @Mock private TypedQuery<Long> countQuery;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant end = Instant.parse("2026-10-02T00:00:00Z");
    private final String actionValue = "FNB_PAYMENT_RECORDED";
    private final String entityValue = "SALES_ORDER";

    @Test
    void pageAndCountApplyIdenticalActualScopeAndFiltersWithStableProjectionAndPagination() {
        stubMandatoryPredicates();
        when(query.where("audit.action")).thenReturn(action);
        when(action.eq(actionValue)).thenReturn(query);
        when(query.where("audit.actor.id")).thenReturn(actor);
        when(actor.eq(actorId)).thenReturn(query);
        when(query.where("audit.entityType")).thenReturn(entity);
        when(entity.eq(entityValue)).thenReturn(query);
        stubProjection();
        MerchantAuditRepository.AuditRow row = new MerchantAuditRepository.AuditRow(UUID.randomUUID(), start,
                actionValue, entityValue, UUID.randomUUID(), actorId, "Owner Name", "Reason", "Details");
        when(projected.getResultList()).thenReturn(List.of(row));
        when(query.getCountQuery()).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(12L);

        MerchantAuditRepository repository = repository();
        assertEquals(List.of(row), repository.page(tenantId, outletId, start, end,
                actionValue, actorId, entityValue, 4, 25));
        assertEquals(12L, repository.count(tenantId, outletId, start, end, actionValue, actorId, entityValue));

        verifyMandatoryPredicates(2);
        verify(query, times(2)).where("audit.action");
        verify(action, times(2)).eq(actionValue);
        verify(query, times(2)).where("audit.actor.id");
        verify(actor, times(2)).eq(actorId);
        verify(query, times(2)).where("audit.entityType");
        verify(entity, times(2)).eq(entityValue);
        verifyNoMoreInteractions(tenant, outlet, time, action, actor, entity);
        InOrder projectionOrder = inOrder(query, selection, projected);
        projectionOrder.verify(query).selectNew(MerchantAuditRepository.AuditRow.class);
        for (String expression : List.of("audit.id", "audit.createdAt", "audit.action", "audit.entityType",
                "audit.entityId", "actor.id", "actor.fullName", "audit.reason", "audit.details")) {
            projectionOrder.verify(selection).with(expression);
        }
        projectionOrder.verify(selection).end();
        projectionOrder.verify(projected).orderByDesc("audit.createdAt");
        projectionOrder.verify(projected).orderByDesc("audit.id");
        projectionOrder.verify(projected).setFirstResult(100);
        projectionOrder.verify(projected).setMaxResults(26);
        projectionOrder.verify(projected).getResultList();
        projectionOrder.verify(query).getCountQuery();
        verify(countQuery).getSingleResult();
    }

    @Test
    void absentOptionalFiltersStillRequireConcreteOutletEqualityForPageAndCount() {
        stubMandatoryPredicates();
        stubProjection();
        when(projected.getResultList()).thenReturn(List.of());
        when(query.getCountQuery()).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);

        assertEquals(List.of(), repository().page(tenantId, outletId, start, end, null, null, null, 0, 1));
        assertEquals(0L, repository().count(tenantId, outletId, start, end, null, null, null));

        // Concrete outlet equality intends to exclude tenant-wide/null-outlet records.
        // Mockito cannot establish database isolation or actual SQL null semantics.
        verifyMandatoryPredicates(2);
        verify(query, never()).where("audit.action");
        verify(query, never()).where("audit.actor.id");
        verify(query, never()).where("audit.entityType");
        verify(projected).setFirstResult(0);
        verify(projected).setMaxResults(2);
        verifyNoMoreInteractions(tenant, outlet, time);
    }

    private void stubMandatoryPredicates() {
        when(criteriaBuilderFactory.create(entityManager, AuditLogEntity.class, "audit")).thenReturn(query);
        when(query.where("audit.tenant.id")).thenReturn(tenant);
        when(tenant.eq(tenantId)).thenReturn(query);
        when(query.where("audit.outlet.id")).thenReturn(outlet);
        when(outlet.eq(outletId)).thenReturn(query);
        when(query.where("audit.createdAt")).thenReturn(time);
        when(time.ge(start)).thenReturn(query);
        when(time.lt(end)).thenReturn(query);
    }

    private void stubProjection() {
        when(query.selectNew(MerchantAuditRepository.AuditRow.class)).thenReturn(selection);
        when(selection.end()).thenReturn(projected);
    }

    private void verifyMandatoryPredicates(int calls) {
        verify(criteriaBuilderFactory, times(calls)).create(entityManager, AuditLogEntity.class, "audit");
        verify(query, times(calls)).leftJoin("audit.actor", "actor");
        verify(query, times(calls)).where("audit.tenant.id");
        verify(tenant, times(calls)).eq(tenantId);
        verify(query, times(calls)).where("audit.outlet.id");
        verify(outlet, times(calls)).eq(outletId);
        verify(query, times(calls * 2)).where("audit.createdAt");
        verify(time, times(calls)).ge(start);
        verify(time, times(calls)).lt(end);
    }

    private MerchantAuditRepository repository() {
        return new MerchantAuditRepository(entityManager, criteriaBuilderFactory);
    }
}
