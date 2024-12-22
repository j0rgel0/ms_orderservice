package com.lox.orderservice.api.models.responses;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single reserved product in the successful InventoryReservedEvent, including
 * quantity, unit price, and total price for that line item.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservedItemEvent {

    private UUID productId;
    private int quantity;
}
