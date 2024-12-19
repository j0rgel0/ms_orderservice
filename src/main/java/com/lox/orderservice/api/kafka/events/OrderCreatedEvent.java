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
public class OrderCreatedEvent implements Event {

    private String eventType;
    private UUID orderId;
    private UUID userId;
    private Double totalAmount;
    private String currency;
    private String status;
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
     * Creates an OrderCreatedEvent from an Order entity.
     *
     * @param order The Order entity.
     * @return An OrderCreatedEvent instance.
     */
    public static OrderCreatedEvent fromOrder(Order order) {
        return OrderCreatedEvent.builder()
                .eventType(EventType.ORDER_CREATED.name())
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount().doubleValue())
                .currency(order.getCurrency())
                .status(order.getStatus().name())
                .timestamp(Instant.now())
                .build();
    }
}
