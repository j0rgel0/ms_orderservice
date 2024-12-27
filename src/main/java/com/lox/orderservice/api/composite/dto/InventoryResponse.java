package com.lox.orderservice.api.composite.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * This matches the structure from the Inventory Service:
 * {
 *   "inventory": { ... },
 *   "product":   { ... }
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryResponse {
    private InventoryDto inventory;
    private ProductDto product;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryDto {
        private UUID inventoryId;
        private UUID productId;
        private Integer availableQuantity;
        private Integer reservedQuantity;
        private Integer reorderLevel;
        private Integer reorderQuantity;
        private Instant lastUpdated;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductDto {
        private UUID productId;
        private String name;
        private String description;
        private Double price;  // or BigDecimal if consistent
        private String category;
        private Integer availableQuantity;
        private Instant createdAt;
        private Instant updatedAt;
    }
}