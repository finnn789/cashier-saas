package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.GatewayEventEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Platform invoices, their payment attempts and gateway events. Tenant scoping is by the caller-supplied tenant id. */
@Repository
@RequiredArgsConstructor
public class PlatformBillingRepository {
    private final EntityManager entityManager;

    public void persist(PlatformInvoiceEntity invoice) {
        entityManager.persist(invoice);
    }

    public void persist(PlatformPaymentAttemptEntity attempt) {
        entityManager.persist(attempt);
    }

    public void persist(GatewayEventEntity event) {
        entityManager.persist(event);
    }

    /** Re-reads the row after the tenant lock so a concurrent transition is seen. */
    public void refresh(PlatformInvoiceEntity invoice) {
        entityManager.refresh(invoice);
    }

    /** Reference only: the audit actor needs the id, not the row. */
    public StaffUserEntity actor(UUID userId) {
        return userId == null ? null : entityManager.getReference(StaffUserEntity.class, userId);
    }

    public Optional<PlatformInvoiceEntity> invoice(UUID tenantId, UUID invoiceId) {
        return entityManager.createQuery("select i from PlatformInvoiceEntity i where i.tenant.id = :tenantId and i.id = :id",
                        PlatformInvoiceEntity.class)
                .setParameter("tenantId", tenantId).setParameter("id", invoiceId).getResultStream().findFirst();
    }

    /** Not tenant scoped: only for the settlement entry point, which then works on the invoice's own tenant. */
    public Optional<PlatformInvoiceEntity> invoiceById(UUID invoiceId) {
        return Optional.ofNullable(entityManager.find(PlatformInvoiceEntity.class, invoiceId));
    }

    public List<PlatformInvoiceEntity> recent(UUID tenantId, int max) {
        return entityManager.createQuery("select i from PlatformInvoiceEntity i where i.tenant.id = :tenantId "
                        + "order by i.issuedAt desc, i.id desc", PlatformInvoiceEntity.class)
                .setParameter("tenantId", tenantId).setMaxResults(max).getResultList();
    }

    /** The ISSUED invoice that can still be paid, if any. */
    public Optional<PlatformInvoiceEntity> openInvoice(UUID tenantId, Instant now) {
        return entityManager.createQuery("select i from PlatformInvoiceEntity i where i.tenant.id = :tenantId "
                        + "and i.status = :issued and i.dueAt > :now", PlatformInvoiceEntity.class)
                .setParameter("tenantId", tenantId).setParameter("issued", PlatformInvoiceEntity.ISSUED)
                .setParameter("now", now).setMaxResults(1).getResultStream().findFirst();
    }

    public List<PlatformInvoiceEntity> dueIssued(UUID tenantId, Instant now) {
        return entityManager.createQuery("select i from PlatformInvoiceEntity i where i.tenant.id = :tenantId "
                        + "and i.status = :issued and i.dueAt <= :now", PlatformInvoiceEntity.class)
                .setParameter("tenantId", tenantId).setParameter("issued", PlatformInvoiceEntity.ISSUED)
                .setParameter("now", now).getResultList();
    }

    public Optional<PlatformPaymentAttemptEntity> attemptByKey(UUID tenantId, String idempotencyKey) {
        return entityManager.createQuery("select a from PlatformPaymentAttemptEntity a where a.tenantId = :tenantId "
                        + "and a.idempotencyKey = :key", PlatformPaymentAttemptEntity.class)
                .setParameter("tenantId", tenantId).setParameter("key", idempotencyKey).getResultStream().findFirst();
    }

    /** Not tenant scoped: only for provider notifications, which carry the provider's own reference. */
    public Optional<PlatformPaymentAttemptEntity> attemptByProviderRef(String providerCode, String providerPaymentRef) {
        return entityManager.createQuery("select a from PlatformPaymentAttemptEntity a where a.providerCode = :code "
                        + "and a.providerPaymentRef = :ref", PlatformPaymentAttemptEntity.class)
                .setParameter("code", providerCode).setParameter("ref", providerPaymentRef).setMaxResults(1).getResultStream().findFirst();
    }

    public boolean gatewayEventExists(String providerCode, String providerAccountRef, String providerEventId) {
        return !entityManager.createQuery("select e.id from GatewayEventEntity e where e.providerCode = :code "
                        + "and e.providerAccountRef = :account and e.providerEventId = :event", UUID.class)
                .setParameter("code", providerCode).setParameter("account", providerAccountRef)
                .setParameter("event", providerEventId).setMaxResults(1).getResultList().isEmpty();
    }

    public List<PlatformPaymentAttemptEntity> pendingAttempts(UUID invoiceId) {
        return entityManager.createQuery("select a from PlatformPaymentAttemptEntity a where a.platformInvoice.id = :invoiceId "
                        + "and a.status = :pending order by a.createdAt desc", PlatformPaymentAttemptEntity.class)
                .setParameter("invoiceId", invoiceId).setParameter("pending", PlatformPaymentAttemptEntity.PENDING).getResultList();
    }

    /** Latest attempt per invoice id; invoices without an attempt are absent from the map. */
    public Map<UUID, PlatformPaymentAttemptEntity> latestAttempts(List<UUID> invoiceIds) {
        Map<UUID, PlatformPaymentAttemptEntity> latest = new HashMap<>();
        if (invoiceIds.isEmpty()) {
            return latest;
        }
        for (PlatformPaymentAttemptEntity attempt : entityManager.createQuery("select a from PlatformPaymentAttemptEntity a "
                        + "where a.platformInvoice.id in :ids order by a.createdAt, a.id", PlatformPaymentAttemptEntity.class)
                .setParameter("ids", invoiceIds).getResultList()) {
            latest.put(attempt.getPlatformInvoice().getId(), attempt);
        }
        return latest;
    }
}
