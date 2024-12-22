package com.lox.orderservice.api.models.enums;

public enum CancellationReason {
    INVENTORY_SERVICE_NO_STOCK,          // Because some products were unavailable
    INVENTORY_SERVICE_PRODUCT_NOT_EXIST, // Because the product does not exist
    INVENTORY_SERVICE_UNRESPONSIVE,      // Because InventoryService is not responding
    PAYMENT_SERVICE_INSUFFICIENT_FUNDS   // Because there are insufficient funds
}
