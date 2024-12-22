package com.lox.orderservice.api.kafka.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lox.orderservice.api.models.responses.ReservedItemEvent;
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
public class ReleaseInventoryCommand implements Event {

    private String eventType;          // e.g. "RESERVE_INVENTORY_COMMAND"
    private UUID trackId;             // correlation/tracking ID
    private UUID orderId;             // order to be reserved
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
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getTimestamp() {
        return timestamp;
    }
}
