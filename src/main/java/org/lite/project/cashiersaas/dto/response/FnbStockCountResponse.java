package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FnbStockCountResponse {
    /** Null only for a replay of a count recorded before stock counts had a header. */
    private UUID stockCountId;
    private List<FnbStockCountLineResponse> lines;
}
