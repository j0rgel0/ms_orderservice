package com.lox.orderservice.api.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("orders")
public class Order {

    @Id
    @Column("order_id")
    private UUID orderId;

    @Column("track_id")
    private UUID trackId;

    @Column("user_id")
    private UUID userId;

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("currency")
    private String currency;

    @Column("status")
    private OrderStatus status;

    @Column("cancellation_reason")
    private CancellationReason cancellationReason;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private List<OrderItem> items;
}
