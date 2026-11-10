package org.lite.project.cashiersaas.dto;

import org.lite.project.cashiersaas.dto.response.*;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.inventory.RecipeLineEntity;
import java.util.List;

public final class FnbCatalogMapper {
    private FnbCatalogMapper() { }
    public static FnbCategoryResponse category(CategoryEntity entity) {
        FnbCategoryResponse response = new FnbCategoryResponse();
        response.setId(entity.getId()); response.setName(entity.getName());
        response.setDisplayOrder(entity.getDisplayOrder()); response.setArchived(entity.isArchived());
        return response;
    }
    public static FnbProductResponse product(ProductEntity entity) {
        FnbProductResponse response = new FnbProductResponse();
        response.setId(entity.getId()); response.setCategoryId(entity.getCategory().getId());
        response.setName(entity.getName()); response.setDescription(entity.getDescription());
        response.setPrice(entity.getPrice()); response.setImageUrl(entity.getImageUrl());
        response.setAvailable(entity.isAvailable()); response.setArchived(entity.isArchived());
        response.setDisplayOrder(entity.getDisplayOrder());
        return response;
    }
    public static FnbOptionGroupResponse group(OptionGroupEntity entity) {
        FnbOptionGroupResponse response = new FnbOptionGroupResponse();
        response.setId(entity.getId()); response.setProductId(entity.getProduct().getId());
        response.setName(entity.getName()); response.setRequired(entity.isRequired());
        response.setMinimumSelections(entity.getMinimumSelections());
        response.setMaximumSelections(entity.getMaximumSelections());
        response.setDisplayOrder(entity.getDisplayOrder());
        return response;
    }
    public static FnbOptionResponse option(ProductOptionEntity entity) {
        FnbOptionResponse response = new FnbOptionResponse();
        response.setId(entity.getId()); response.setOptionGroupId(entity.getOptionGroup().getId());
        response.setName(entity.getName()); response.setPriceAdjustment(entity.getPriceAdjustment());
        response.setAvailable(entity.isAvailable()); response.setDisplayOrder(entity.getDisplayOrder());
        return response;
    }
    public static FnbRecipeResponse recipe(List<RecipeLineEntity> lines) {
        FnbRecipeResponse response = new FnbRecipeResponse();
        response.setLines(lines.stream().map(FnbCatalogMapper::recipeLine).toList());
        return response;
    }
    private static FnbRecipeResponse.Line recipeLine(RecipeLineEntity entity) {
        FnbRecipeResponse.Line line = new FnbRecipeResponse.Line();
        line.setInventoryItemId(entity.getInventoryItem().getId());
        line.setInventoryItemName(entity.getInventoryItem().getName());
        line.setUnit(entity.getInventoryItem().getUnit());
        line.setQuantityPerUnit(entity.getQuantityPerUnit());
        return line;
    }
}
