package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter @Setter
public class PublicMenuResponse {
    private String outletName;
    private String tableName;
    private BigDecimal taxRate;
    private BigDecimal serviceChargeRate;
    private List<Category> categories;

    @Getter @Setter public static class Category {
        private UUID id;
        private String name;
        private List<Product> products;
    }
    @Getter @Setter public static class Product {
        private UUID id;
        private String name;
        private String description;
        private BigDecimal price;
        private String imageUrl;
        private boolean available;
        private List<OptionGroup> groups;
    }
    @Getter @Setter public static class OptionGroup {
        private UUID id;
        private String name;
        private boolean required;
        private int minimumSelections;
        private int maximumSelections;
        private List<Option> options;
    }
    @Getter @Setter public static class Option {
        private UUID id;
        private String name;
        private BigDecimal priceAdjustment;
        private boolean available;
    }
}
