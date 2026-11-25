package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class FnbCatalogCopyResponse {
    private int categoriesCreated;
    private int categoriesReused;
    private int products;
    private int optionGroups;
    private int options;
    private int inventoryItems;
    private int recipeLines;
    private int recipeLinesSkipped;
}
