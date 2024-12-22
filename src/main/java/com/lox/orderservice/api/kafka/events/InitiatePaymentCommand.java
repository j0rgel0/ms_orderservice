package com.lox.orderservice.api.kafka.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lox.orderservice.api.models.OrderItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiatePaymentCommand implements Event {

    private String eventType;
    private UUID trackId;
    private UUID orderId;
    private UUID userId;
    private Double totalAmount;
    private String currency;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    private List<OrderItemEvent> items;
    private String cancellationReason;

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

    private Instant timestamp;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OrderItemEvent {

        private UUID productId;
        private int quantity;
    }
}
