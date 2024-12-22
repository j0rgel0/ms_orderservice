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
public class InventoryReserveFailedEvent implements Event {

    private String eventType;
    private UUID orderId;
    private String reason;
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

    public static InventoryReserveFailedEvent fromOrder(Order order, String reason) {
        return InventoryReserveFailedEvent.builder()
                .eventType(EventType.INVENTORY_RESERVE_FAILED.name())
                .orderId(order.getOrderId())
                .reason(reason)
                .timestamp(Instant.now())
                .build();
    }
}
