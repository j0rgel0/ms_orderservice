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
public class PaymentInitiatedEvent implements Event {

    private String eventType;
    private UUID orderId;
    private Double amount;
    private String currency;
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
     * Creates a PaymentInitiatedEvent from an Order entity.
     *
     * @param order The Order entity.
     * @return A PaymentInitiatedEvent instance.
     */
    public static PaymentInitiatedEvent fromOrder(Order order) {
        return PaymentInitiatedEvent.builder()
                .eventType(EventType.PAYMENT_INITIATED.name())
                .orderId(order.getOrderId())
                .amount(order.getTotalAmount().doubleValue())
                .currency(order.getCurrency())
                .timestamp(Instant.now())
                .build();
    }
}
