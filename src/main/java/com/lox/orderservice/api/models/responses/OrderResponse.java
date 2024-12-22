package com.lox.orderservice.api.models.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class OrderResponse {

    private UUID trackId;
    private UUID orderId;
    private UUID userId;
    private String currency;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<OrderItemResponse> items;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cancellationReason;
}
