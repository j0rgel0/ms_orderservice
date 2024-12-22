package com.lox.orderservice.api.models.responses;

import com.lox.orderservice.api.kafka.events.Event;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Command to instruct inventory to reserve items for a given order, but without 'orderTotal',
 * 'unitPrice', or 'totalPrice'.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReserveInventoryCommand implements Event {

    private String eventType;
    private UUID trackId;
    private UUID orderId;
    private List<ReservedItemEvent> items;
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
