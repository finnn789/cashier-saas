package org.lite.project.cashiersaas.dto.response.platform;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PlatformPageResponse<T> {
    private List<T> items;
    private int page;
    private int size;
    private long total;
}
