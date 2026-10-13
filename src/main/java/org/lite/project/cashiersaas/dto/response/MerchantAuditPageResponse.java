package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantAuditPageResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private String timeZone;
    private List<MerchantAuditEntryResponse> entries;
    private int page;
    private int size;
    private long total;
    private boolean hasNext;
}
