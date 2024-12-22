package com.lox.orderservice.api.services;

import com.lox.orderservice.api.models.OrderPage;
import com.lox.orderservice.api.models.dto.OrderRequest;
import com.lox.orderservice.api.models.dto.OrderResponse;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface OrderService {

    Mono<OrderResponse> createOrder(OrderRequest orderRequest);

    Mono<OrderResponse> getOrderById(UUID orderId);

    Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus);

    Mono<Void> deleteOrder(UUID orderId);

    Mono<OrderPage> listOrders(String status, UUID userId, java.time.Instant startDate,
            java.time.Instant endDate, int page, int size);

    Mono<Void> handleOrderStatusEvent(String eventPayload);
}
