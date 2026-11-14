package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FnbStockCountResponse {
    private List<FnbStockCountLineResponse> lines;
}
