package com.lox.orderservice.api.controllers;

import com.lox.orderservice.api.dto.OrderRequest;
import com.lox.orderservice.api.dto.OrderResponse;
import com.lox.orderservice.api.models.OrderPage;
import com.lox.orderservice.api.services.OrderService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    /**
     * Creates a new order.
     *
     * @param orderRequest The order creation request payload.
     * @return A Mono emitting the OrderResponse.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderResponse> createOrder(@Valid @RequestBody OrderRequest orderRequest) {
        log.info("Received request to create order: {}", orderRequest);
        return orderService.createOrder(orderRequest)
                .doOnSuccess(
                        orderResponse -> log.info("Order created successfully: {}", orderResponse))
                .doOnError(error -> log.info("Error creating order: {}", error.getMessage()));
    }

    /**
     * Retrieves an order by its ID.
     *
     * @param orderId The ID of the order to retrieve.
     * @return A Mono emitting the OrderResponse.
     */
    @GetMapping("/{orderId}")
    public Mono<OrderResponse> getOrderById(@PathVariable UUID orderId) {
        log.info("Received request to get order with ID: {}", orderId);
        return orderService.getOrderById(orderId)
                .doOnSuccess(orderResponse -> log.info("Order retrieved successfully: {}",
                        orderResponse))
                .doOnError(error -> log.info("Error retrieving order with ID {}: {}", orderId,
                        error.getMessage()));
    }

    /**
     * Updates the status of an existing order.
     *
     * @param orderId   The ID of the order to update.
     * @param newStatus The new status to set.
     * @return A Mono emitting the updated OrderResponse.
     */
    @PutMapping("/{orderId}/status")
    public Mono<OrderResponse> updateOrderStatus(@PathVariable UUID orderId,
            @RequestBody String newStatus) {
        log.info("Received request to update status of order ID {} to '{}'", orderId, newStatus);
        return orderService.updateOrderStatus(orderId, newStatus)
                .doOnSuccess(orderResponse -> log.info("Order status updated successfully: {}",
                        orderResponse))
                .doOnError(error -> log.info("Error updating status of order ID {}: {}", orderId,
                        error.getMessage()));
    }

    /**
     * Deletes an order by its ID.
     *
     * @param orderId The ID of the order to delete.
     * @return A Mono signaling completion.
     */
    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteOrder(@PathVariable UUID orderId) {
        log.info("Received request to delete order with ID: {}", orderId);
        return orderService.deleteOrder(orderId)
                .doOnSuccess(aVoid -> log.info("Order with ID {} deleted successfully", orderId))
                .doOnError(error -> log.info("Error deleting order with ID {}: {}", orderId,
                        error.getMessage()));
    }

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
    @GetMapping
    public Mono<OrderPage> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Instant startDate,
            @RequestParam(required = false) Instant endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info(
                "Received request to list orders with filters - Status: {}, UserID: {}, StartDate: {}, EndDate: {}, Page: {}, Size: {}",
                status, userId, startDate, endDate, page, size);
        return orderService.listOrders(status, userId, startDate, endDate, page, size)
                .doOnSuccess(orderPage -> log.info("Orders listed successfully: {}", orderPage))
                .doOnError(error -> log.info("Error listing orders: {}", error.getMessage()));
    }

    /**
     * Handles payment gateway callbacks to update order statuses.
     *
     * @param callbackPayload The callback payload from the payment gateway.
     * @return A Mono signaling completion.
     */
    @PostMapping("/callback")
    @ResponseStatus(HttpStatus.OK)
    public Mono<Void> handlePaymentCallback(@RequestBody String callbackPayload) {
        log.info("Received payment callback with payload: {}", callbackPayload);
        return orderService.handlePaymentCallback(callbackPayload)
                .doOnSuccess(aVoid -> log.info("Payment callback processed successfully"))
                .doOnError(error -> log.info("Error processing payment callback: {}",
                        error.getMessage()));
    }
}
