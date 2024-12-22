package com.lox.orderservice.api.kafka.events;

import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.dto.ReservedItemEvent;
import java.math.BigDecimal;
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
    private List<ReservedItemEvent> items;
    private BigDecimal totalAmount;
    private Double orderTotal;
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

}
