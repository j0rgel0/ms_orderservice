package com.lox.orderservice.api.repositories.r2dbc;

import com.lox.orderservice.api.models.OrderItem;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface OrderItemRepository extends ReactiveCrudRepository<OrderItem, UUID> {

    Flux<OrderItem> findByOrderId(UUID orderId);
}
