package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="gateway_events",uniqueConstraints=@UniqueConstraint(columnNames={"provider_code","provider_account_ref","provider_event_id"}))
@NoArgsConstructor
public class GatewayEventEntity extends BaseEntity {
    @Column(name="provider_code",nullable=false,length=50)
    private String providerCode;
    @Column(name="provider_account_ref",nullable=false,length=120)
    private String providerAccountRef;
    @Column(name="provider_event_id",nullable=false,length=160)
    private String providerEventId;
    @Column(name="provider_payment_ref",length=160)
    private String providerPaymentRef;
    @Column(name="normalized_amount",precision=19,scale=2)
    private BigDecimal normalizedAmount;
    @Column(name="currency_code",length=3)
    private String currencyCode;
    @Column(name="normalized_status",length=20)
    private String normalizedStatus;
    @Column(name="matched_attempt_id")
    private java.util.UUID matchedAttemptId;
    @Column(name="event_type",nullable=false,length=100)
    private String eventType;
    @Column(name="payload_digest",nullable=false,length=64)
    private String payloadDigest;
    @Column(name="received_at",nullable=false)
    private Instant receivedAt;
    private Instant processedAt;
    @Column(name="processing_status",nullable=false,length=16)
    private String processingStatus;
}
