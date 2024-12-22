package com.lox.orderservice.api.kafka.events;

import com.lox.orderservice.api.models.Order;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReserveInventoryCommand implements Event {

    private String eventType;
    private UUID orderId;
    private UUID productId;
    private int quantity;
    private Instant timestamp;

    @Override
    public String getEventType() {
        return eventType;
    }

    @Override
    public UUID getOrderId() {
        return orderId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static ReserveInventoryCommand fromOrder(Order order) {
        return ReserveInventoryCommand.builder()
                .eventType(EventType.RESERVE_INVENTORY_COMMAND.name())
                .orderId(order.getOrderId())
                .productId(order.getItems().getFirst().getProductId())
                .quantity(order.getItems().getFirst().getQuantity())
                .timestamp(Instant.now())
                .build();
    }
}
