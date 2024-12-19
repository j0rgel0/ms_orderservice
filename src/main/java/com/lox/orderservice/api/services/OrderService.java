package com.lox.orderservice.api.services;

import com.lox.orderservice.api.dto.OrderRequest;
import com.lox.orderservice.api.dto.OrderResponse;
import com.lox.orderservice.api.models.OrderPage;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface OrderService {

    /**
     * Creates a new order.
     *
     * @param orderRequest The order creation request payload.
     * @return A Mono emitting the OrderResponse.
     */
    Mono<OrderResponse> createOrder(OrderRequest orderRequest);

    /**
     * Retrieves an order by its ID.
     *
     * @param orderId The ID of the order to retrieve.
     * @return A Mono emitting the OrderResponse.
     */
    Mono<OrderResponse> getOrderById(UUID orderId);

    /**
     * Updates the status of an existing order.
     *
     * @param orderId   The ID of the order to update.
     * @param newStatus The new status to set.
     * @return A Mono emitting the updated OrderResponse.
     */
    Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus);

    /**
     * Deletes an order by its ID.
     *
     * @param orderId The ID of the order to delete.
     * @return A Mono signaling completion.
     */
    Mono<Void> deleteOrder(UUID orderId);

    /**
     * Lists orders based on optional filters with pagination.
     *
     * @param status    Optional order status filter.
     * @param userId    Optional user ID filter.
     * @param startDate Optional start date for filtering.
     * @param endDate   Optional end date for filtering.
     * @param page      Page number for pagination.
     * @param size      Page size for pagination.
     * @return A Mono emitting the OrderPage containing order responses.
     */
    Mono<OrderPage> listOrders(String status, UUID userId, Instant startDate, Instant endDate,
            int page, int size);

    /**
     * Handles payment callbacks to update order statuses.
     *
     * @param callbackPayload The callback payload from the payment gateway.
     * @return A Mono signaling completion.
     */
    Mono<Void> handlePaymentCallback(String callbackPayload);
}
