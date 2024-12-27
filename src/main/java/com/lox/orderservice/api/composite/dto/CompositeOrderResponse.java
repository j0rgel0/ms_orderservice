package com.lox.orderservice.api.composite.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeOrderResponse {

    private UUID orderId;
    private UUID trackId;
    private UUID userId;
    private BigDecimal totalAmount;
    private String currency;
    private String status;
    private String cancellationReason;
    private Instant createdAt;
    private Instant updatedAt;

    // We keep "product" data in each OrderItem, but no "inventory"
    private List<OrderItemInfo> orderItems;

    private List<PaymentInfo> payments;

    /**
     * Top-level list holding the inventory details for each distinct product in the order.
     */
    private List<InventoryData> currentInventories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemInfo {
        private UUID orderItemId;
        private UUID productId;
        private Integer quantity;
        private BigDecimal price;
        private Instant createdAt;
        private Instant updatedAt;

        // Only product details here
        private ProductInfo product;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInfo {
        private UUID paymentId;
        private UUID orderId;
        private UUID trackId;
        private UUID userId;
        private BigDecimal amount;
        private String currency;
        private String paymentMethod;
        private String status;
        private String transactionId;
        private String failureReason;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductInfo {
        private UUID productId;
        private String name;
        private String description;
        private BigDecimal price;
        private String category;
        private Integer availableQuantity;
        private Instant createdAt;
        private Instant updatedAt;
    }

    /**
     * We'll store the "inventory + product" data from InventoryService.
     * This is a flattened version of your InventoryResponse.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryData {
        private UUID inventoryId;
        private UUID productId;
        private Integer availableQuantity;
        private Integer reservedQuantity;
        private Integer reorderLevel;
        private Integer reorderQuantity;
        private Instant lastUpdated;

        // Optionally copy product fields from the InventoryService response
        private ProductInfo product;
    }
}
