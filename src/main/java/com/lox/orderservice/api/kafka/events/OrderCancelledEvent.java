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
public class OrderCancelledEvent implements Event {

    private String eventType;
    private UUID orderId;
    private String status;
    private String failureReason;
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
     * Creates an OrderCancelledEvent from an Order entity.
     *
     * @param order         The Order entity.
     * @param failureReason The reason for cancellation.
     * @return An OrderCancelledEvent instance.
     */
    public static OrderCancelledEvent fromOrder(Order order, String failureReason) {
        return OrderCancelledEvent.builder()
                .eventType(EventType.ORDER_CANCELLED.name())
                .orderId(order.getOrderId())
                .status(order.getStatus().name())
                .failureReason(failureReason)
                .timestamp(Instant.now())
                .build();
    }
}
