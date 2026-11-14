package org.lite.project.cashiersaas.grpc.mapper;

import com.cashier.grpc.v1.CatalogItem;
import com.cashier.grpc.v1.GetCatalogItemResponse;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class GrpcCatalogMapper {

    public CatalogItem toItem(ProductEntity entity) {

        return toCatalogItem(entity);
    }

    public List<CatalogItem> toItems(List<ProductEntity> entities) {

        return toCatalogItems(entities);
    }

    public GetCatalogItemResponse toResponse(String tenantId, List<ProductEntity> entities) {
        return toGetCatalogItemResponse(tenantId, entities);
    }

    public static CatalogItem toCatalogItem(ProductEntity entity) {
        if (entity == null) {
            return CatalogItem.getDefaultInstance();
        }
        CatalogItem.Builder builder = CatalogItem.newBuilder();
        if (entity.getId() != null) {
            builder.setId(entity.getId().toString());
        }
        if (entity.getName() != null) {
            builder.setName(entity.getName());
        }
        if (entity.getPrice() != null) {
            builder.setPriceAmount(entity.getPrice().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        builder.setIsAvailable(entity.isAvailable());

        CategoryEntity category = entity.getCategory();
        if (category != null && category.getName() != null) {
            builder.setCategory(category.getName());
        }
        return builder.build();
    }

    public static List<CatalogItem> toCatalogItems(List<ProductEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<CatalogItem> items = new ArrayList<>(entities.size());
        for (ProductEntity entity : entities) {
            items.add(toCatalogItem(entity));
        }
        return items;
    }

    public static GetCatalogItemResponse toGetCatalogItemResponse(String tenantId, List<ProductEntity> entities) {
        GetCatalogItemResponse.Builder builder = GetCatalogItemResponse.newBuilder();
        if (tenantId != null) {
            builder.setTenantId(tenantId);
        }
        if (entities != null) {
            for (ProductEntity entity : entities) {
                builder.addItems(toCatalogItem(entity));
            }
        }
        return builder.build();
    }
}
