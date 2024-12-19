package com.lox.orderservice.api.kafka.events;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class InventoryReservedEvent implements Event {

    private String eventType;
    private UUID orderId;
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
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Creates an InventoryReservedEvent from an Order entity.
     *
     * @param order The Order entity.
     * @return An InventoryReservedEvent instance.
     */
    public static InventoryReservedEvent fromOrder(Order order) {
        return InventoryReservedEvent.builder()
                .eventType(EventType.INVENTORY_RESERVED.name())
                .orderId(order.getOrderId())
                .timestamp(Instant.now())
                .build();
    }
}