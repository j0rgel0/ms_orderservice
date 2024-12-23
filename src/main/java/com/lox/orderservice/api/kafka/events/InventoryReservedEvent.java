package com.lox.orderservice.api.kafka.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lox.orderservice.api.models.responses.ReasonDetail;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Optional: If you want to ignore other unknown properties
public class InventoryReservedEvent {
    private String eventType;
    private UUID trackId;
    private UUID orderId;
    private List<Item> items;
    private BigDecimal orderTotal;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Instant timestamp;

    private List<ReasonDetail> reasons; // Updated field

    @Data
    public static class Item {
        private UUID productId;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }
}
