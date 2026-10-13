package org.lite.project.cashiersaas.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
public class MerchantAuditFilter {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    @Size(max = 80)
    private String action;

    private UUID actorId;

    @Size(max = 80)
    private String entityType;

    @Min(0)
    @Max(1_000_000)
    private Integer page;

    @Min(1)
    @Max(100)
    private Integer size;
}
