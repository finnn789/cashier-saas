package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FnbShiftPageResponse {
    private List<FnbShiftResponse> shifts;
    private int page;
    private int size;
    private boolean hasNext;
}
