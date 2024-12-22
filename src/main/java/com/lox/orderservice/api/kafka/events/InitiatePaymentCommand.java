package com.lox.orderservice.api.kafka.events;

import com.lox.orderservice.api.models.Order;
import java.math.BigDecimal;
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
public class InitiatePaymentCommand implements Event {

    private String eventType;
    private UUID orderId;
    private BigDecimal totalAmount;
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

    public static InitiatePaymentCommand fromOrder(Order order) {
        return InitiatePaymentCommand.builder()
                .eventType(EventType.INITIATE_PAYMENT_COMMAND.name())
                .orderId(order.getOrderId())
                .totalAmount(order.getTotalAmount())
                .timestamp(Instant.now())
                .build();
    }
}
