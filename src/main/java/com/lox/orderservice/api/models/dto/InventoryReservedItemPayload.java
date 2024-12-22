package com.lox.orderservice.api.models.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservedItemPayload {

    private UUID productId;
    private Integer quantity;
    private Double unitPrice;
    private Double totalPrice;
}
