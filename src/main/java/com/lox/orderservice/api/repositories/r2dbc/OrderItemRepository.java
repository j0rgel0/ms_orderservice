package com.lox.orderservice.api.repositories.r2dbc;

import com.lox.orderservice.api.models.OrderItem;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderItemRepository extends ReactiveCrudRepository<OrderItem, UUID> {

    Flux<OrderItem> findByOrderId(UUID orderId);

//    @Query("SELECT * FROM order_items WHERE order_id = :orderId AND product_id = :productId")
//    Mono<OrderItem> findByOrderIdAndProductId(UUID orderId, UUID productId);

    Mono<OrderItem> findByOrderIdAndProductId(UUID orderId, UUID productId);


}
