package org.lite.project.cashiersaas.dto.response;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter @Setter
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class PublicMenuResponse {
    private String qrType;
    private List<org.lite.project.cashiersaas.enums.ServiceType> serviceTypes;
    private String outletName;
    private String tableName;
    private BigDecimal taxRate;
    private BigDecimal serviceChargeRate;
    private List<Category> categories;

    @Getter @Setter @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class Category {
        private UUID id;
        private String name;
        private List<Product> products;
    }
    @Getter @Setter @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class Product {
        private UUID id;
        private String name;
        private String description;
        private BigDecimal price;
        private String imageUrl;
        private boolean available;
        private List<OptionGroup> groups;
    }
    @Getter @Setter @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class OptionGroup {
        private UUID id;
        private String name;
        private boolean required;
        private int minimumSelections;
        private int maximumSelections;
        private List<Option> options;
    }
    @Getter @Setter @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class Option {
        private UUID id;
        private String name;
        private BigDecimal priceAdjustment;
        private boolean available;
    }
}
