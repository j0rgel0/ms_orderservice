package com.lox.orderservice.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lox.orderservice.api.dto.OrderRequest;
import com.lox.orderservice.api.dto.OrderResponse;
import com.lox.orderservice.api.exceptions.InvalidOrderStatusException;
import com.lox.orderservice.api.exceptions.OrderNotFoundException;
import com.lox.orderservice.api.kafka.events.Event;
import com.lox.orderservice.api.kafka.events.EventType;
import com.lox.orderservice.api.kafka.events.InventoryReleasedEvent;
import com.lox.orderservice.api.kafka.events.InventoryReservedEvent;
import com.lox.orderservice.api.kafka.events.OrderCancelledEvent;
import com.lox.orderservice.api.kafka.events.OrderCompletedEvent;
import com.lox.orderservice.api.kafka.events.OrderCreatedEvent;
import com.lox.orderservice.api.kafka.events.OrderRemovedEvent;
import com.lox.orderservice.api.kafka.events.OrderUpdatedEvent;
import com.lox.orderservice.api.kafka.events.PaymentCancelledEvent;
import com.lox.orderservice.api.kafka.events.PaymentInitiatedEvent;
import com.lox.orderservice.api.kafka.topics.KafkaTopics;
import com.lox.orderservice.api.mappers.OrderMapper;
import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.OrderItem;
import com.lox.orderservice.api.models.OrderPage;
import com.lox.orderservice.api.models.OrderStatus;
import com.lox.orderservice.api.repositories.r2dbc.OrderItemRepository;
import com.lox.orderservice.api.repositories.r2dbc.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final KafkaSender<String, Object> kafkaSender;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

    @Override
    @Transactional
    public Mono<OrderResponse> createOrder(OrderRequest orderRequest) {
        log.info("Creating order with request: {}", orderRequest);

        // Calculate the total amount
        BigDecimal totalAmount = orderRequest.getItems().stream()
                .map(item -> item.getPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("Total amount calculated: {}", totalAmount);

        // Map OrderItemRequest to OrderItem
        List<OrderItem> orderItems = orderRequest.getItems().stream()
                .map(itemRequest -> OrderItem.builder()
                        .productId(itemRequest.getProductId())
                        .quantity(itemRequest.getQuantity())
                        .price(itemRequest.getPrice())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build())
                .collect(Collectors.toList());
        log.info("Mapped OrderItems: {}", orderItems);

        // Create Order entity without items
        Order order = Order.builder()
                .userId(orderRequest.getUserId())
                .totalAmount(totalAmount)
                .currency(orderRequest.getCurrency())
                .status(OrderStatus.CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        log.info("Order entity created: {}", order);

        return orderRepository.save(order)
                .doOnSuccess(savedOrder -> log.info("Order saved to repository: {}", savedOrder))
                .flatMap(savedOrder -> {
                    // Assign orderId to each OrderItem
                    orderItems.forEach(item -> item.setOrderId(savedOrder.getOrderId()));
                    log.info("Setting orderId in OrderItems: {}", orderItems);

                    // Save all OrderItems
                    return orderItemRepository.saveAll(orderItems)
                            .collectList()
                            .doOnNext(savedItems -> log.info("OrderItems saved: {}", savedItems))
                            .then(
                                    // Emit events
                                    emitEvents(
                                            OrderCreatedEvent.fromOrder(savedOrder),
                                            InventoryReservedEvent.fromOrder(savedOrder),
                                            PaymentInitiatedEvent.fromOrder(savedOrder)
                                    )
                                            .doOnSuccess(aVoid -> log.info(
                                                    "All events emitted successfully for order ID: {}",
                                                    savedOrder.getOrderId()))
                                            .doOnError(error -> log.error(
                                                    "Error emitting events for order ID {}: {}",
                                                    savedOrder.getOrderId(), error.getMessage()))
                                            .thenReturn(orderMapper.toOrderResponse(savedOrder))
                            );
                })
                .doOnSuccess(
                        orderResponse -> log.info("Order created successfully: {}", orderResponse))
                .doOnError(error -> log.error("Error creating order: {}", error.getMessage()));
    }

    @Override
    public Mono<OrderResponse> getOrderById(UUID orderId) {
        log.info("Retrieving order with ID: {}", orderId);
        return orderRepository.findById(orderId)
                .doOnSuccess(order -> {
                    if (order != null) {
                        log.info("Order found: {}", order);
                    }
                })
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> {
                    // Retrieve associated OrderItems
                    return orderItemRepository.findByOrderId(orderId)
                            .collectList()
                            .map(items -> {
                                order.setItems(items);
                                return order;
                            });
                })
                .map(orderMapper::toOrderResponse)
                .doOnSuccess(orderResponse -> log.info("Order retrieved successfully: {}",
                        orderResponse))
                .doOnError(error -> log.error("Error retrieving order with ID {}: {}", orderId,
                        error.getMessage()));
    }

    @Override
    @Transactional
    public Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus) {
        log.info("Updating status of order ID {} to '{}'", orderId, newStatus);
        return orderRepository.findById(orderId)
                .doOnSuccess(order -> {
                    if (order != null) {
                        log.info("Order found for update: {}", order);
                    }
                })
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(existingOrder -> {
                    OrderStatus updatedStatus;
                    try {
                        updatedStatus = OrderStatus.valueOf(newStatus.toUpperCase());
                        log.info("Parsed new status: {}", updatedStatus);
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid order status provided: {}", newStatus);
                        return Mono.error(new InvalidOrderStatusException(
                                "Invalid order status: " + newStatus));
                    }

                    existingOrder.setStatus(updatedStatus);
                    existingOrder.setUpdatedAt(Instant.now());
                    log.info("Order status updated in entity: {}", existingOrder);

                    return orderRepository.save(existingOrder)
                            .doOnSuccess(
                                    updatedOrder -> log.info("Order saved with updated status: {}",
                                            updatedOrder))
                            .flatMap(updatedOrder -> {
                                // Emit OrderUpdatedEvent
                                OrderUpdatedEvent orderUpdatedEvent = OrderUpdatedEvent.fromOrder(
                                        updatedOrder);
                                log.info("Emitting OrderUpdatedEvent: {}", orderUpdatedEvent);

                                if (updatedStatus == OrderStatus.CANCELLED) {
                                    InventoryReleasedEvent inventoryReleasedEvent = InventoryReleasedEvent.fromOrder(
                                            updatedOrder);
                                    PaymentCancelledEvent paymentCancelledEvent = PaymentCancelledEvent.fromOrder(
                                            updatedOrder);
                                    log.info("Emitting InventoryReleasedEvent: {}",
                                            inventoryReleasedEvent);
                                    log.info("Emitting PaymentCancelledEvent: {}",
                                            paymentCancelledEvent);
                                    return emitEvents(orderUpdatedEvent, inventoryReleasedEvent,
                                            paymentCancelledEvent)
                                            .doOnSuccess(aVoid -> log.info(
                                                    "All compensating events emitted for cancelled order ID: {}",
                                                    orderId))
                                            .doOnError(error -> log.error(
                                                    "Error emitting compensating events for order ID {}: {}",
                                                    orderId, error.getMessage()))
                                            .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                                }

                                return emitEvent(orderUpdatedEvent)
                                        .doOnSuccess(aVoid -> log.info(
                                                "OrderUpdatedEvent emitted for order ID: {}",
                                                orderId))
                                        .doOnError(error -> log.error(
                                                "Error emitting OrderUpdatedEvent for order ID {}: {}",
                                                orderId, error.getMessage()))
                                        .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                            });
                })
                .doOnSuccess(orderResponse -> log.info("Order status updated successfully: {}",
                        orderResponse))
                .doOnError(error -> log.error("Error updating status of order ID {}: {}", orderId,
                        error.getMessage()));
    }

    @Override
    @Transactional
    public Mono<Void> deleteOrder(UUID orderId) {
        log.info("Deleting order with ID: {}", orderId);
        return orderRepository.findById(orderId)
                .doOnSuccess(order -> {
                    if (order != null) {
                        log.info("Order found for deletion: {}", order);
                    }
                })
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> orderRepository.delete(order)
                        .doOnSuccess(
                                aVoid -> log.info("Order deleted from repository: {}", orderId))
                        .then(
                                emitEvent(OrderRemovedEvent.fromOrder(order))
                                        .doOnSuccess(aVoid -> log.info(
                                                "OrderRemovedEvent emitted for order ID: {}",
                                                orderId))
                                        .doOnError(error -> log.error(
                                                "Error emitting OrderRemovedEvent for order ID {}: {}",
                                                orderId, error.getMessage()))
                        )
                )
                .then()
                .doOnSuccess(
                        aVoid -> log.info("Order deletion process completed for ID: {}", orderId))
                .doOnError(error -> log.error("Error deleting order with ID {}: {}", orderId,
                        error.getMessage()));
    }

    @Override
    public Mono<OrderPage> listOrders(String status, UUID userId, Instant startDate,
            Instant endDate, int page, int size) {
        log.info(
                "Listing orders with filters - Status: {}, UserID: {}, StartDate: {}, EndDate: {}, Page: {}, Size: {}",
                status, userId, startDate, endDate, page, size);

        PageRequest pageRequest = PageRequest.of(page, size);
        log.info("PageRequest created: {}", pageRequest);

        return orderRepository.findOrdersByFilters(status, userId, startDate, endDate, pageRequest)
                .flatMap(order -> {
                    // Retrieve associated OrderItems
                    return orderItemRepository.findByOrderId(order.getOrderId())
                            .collectList()
                            .map(items -> {
                                order.setItems(items);
                                return order;
                            });
                })
                .map(orderMapper::toOrderResponse)
                .doOnNext(
                        orderResponse -> log.debug("Fetched order for listing: {}", orderResponse))
                .collectList()
                .zipWith(orderRepository.countOrdersByFilters(status, userId, startDate, endDate))
                .map(tuple -> {
                    OrderPage orderPage = OrderPage.builder()
                            .orders(tuple.getT1())
                            .totalElements(tuple.getT2())
                            .totalPages((int) Math.ceil((double) tuple.getT2() / size))
                            .currentPage(page)
                            .build();
                    log.info("OrderPage created: {}", orderPage);
                    return orderPage;
                })
                .doOnSuccess(orderPage -> log.info("Orders listed successfully: {}", orderPage))
                .doOnError(error -> log.error("Error listing orders: {}", error.getMessage()));
    }


    @Override
    @Transactional
    public Mono<Void> handlePaymentCallback(String callbackPayload) {
        log.info("Handling payment callback with payload: {}", callbackPayload);
        try {
            JsonNode jsonNode = objectMapper.readTree(callbackPayload);
            UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
            String status = jsonNode.get("status").asText();
            String transactionId =
                    jsonNode.has("transactionId") ? jsonNode.get("transactionId").asText() : null;
            String failureReason =
                    jsonNode.has("failureReason") ? jsonNode.get("failureReason").asText() : null;

            log.info(
                    "Parsed callback - OrderID: {}, Status: {}, TransactionID: {}, FailureReason: {}",
                    orderId, status, transactionId, failureReason);

            return orderRepository.findById(orderId)
                    .doOnSuccess(order -> {
                        if (order != null) {
                            log.info("Order found for payment callback: {}", order);
                        }
                    })
                    .switchIfEmpty(Mono.error(
                            new OrderNotFoundException("Order not found with ID: " + orderId)))
                    .flatMap(order -> {
                        if ("COMPLETED".equalsIgnoreCase(status)) {
                            order.setStatus(OrderStatus.COMPLETED);
                            log.info("Order status set to COMPLETED for order ID: {}", orderId);
                        } else if ("FAILED".equalsIgnoreCase(status)) {
                            order.setStatus(OrderStatus.CANCELLED);
                            log.info("Order status set to CANCELLED for order ID: {}", orderId);
                        } else {
                            log.error("Invalid status in callback: {}", status);
                            return Mono.error(new InvalidOrderStatusException(
                                    "Invalid status in callback: " + status));
                        }
                        order.setUpdatedAt(Instant.now());
                        log.info("Order updated with new status: {}", order);

                        return orderRepository.save(order)
                                .doOnSuccess(updatedOrder -> log.info(
                                        "Order saved after payment callback: {}", updatedOrder))
                                .flatMap(updatedOrder -> {
                                    if ("COMPLETED".equalsIgnoreCase(status)) {
                                        OrderCompletedEvent orderCompletedEvent = OrderCompletedEvent.fromOrder(
                                                updatedOrder);
                                        log.info("Emitting OrderCompletedEvent: {}",
                                                orderCompletedEvent);
                                        return emitEvent(orderCompletedEvent)
                                                .doOnSuccess(aVoid -> log.info(
                                                        "OrderCompletedEvent emitted for order ID: {}",
                                                        orderId))
                                                .doOnError(error -> log.error(
                                                        "Error emitting OrderCompletedEvent for order ID {}: {}",
                                                        orderId, error.getMessage()));
                                    } else if ("FAILED".equalsIgnoreCase(status)) {
                                        OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.fromOrder(
                                                updatedOrder, failureReason);
                                        InventoryReleasedEvent inventoryReleasedEvent = InventoryReleasedEvent.fromOrder(
                                                updatedOrder);
                                        log.info("Emitting OrderCancelledEvent: {}",
                                                orderCancelledEvent);
                                        log.info("Emitting InventoryReleasedEvent: {}",
                                                inventoryReleasedEvent);
                                        return emitEvents(orderCancelledEvent,
                                                inventoryReleasedEvent)
                                                .doOnSuccess(aVoid -> log.info(
                                                        "Compensating events emitted for failed payment of order ID: {}",
                                                        orderId))
                                                .doOnError(error -> log.error(
                                                        "Error emitting compensating events for order ID {}: {}",
                                                        orderId, error.getMessage()));
                                    }
                                    return Mono.empty();
                                });
                    })
                    .then()
                    .doOnSuccess(aVoid -> log.info(
                            "Payment callback handled successfully for order ID: {}", orderId))
                    .doOnError(error -> log.error("Error handling payment callback: {}",
                            error.getMessage()));
        } catch (Exception e) {
            log.error("Invalid callback payload: {}", callbackPayload, e);
            return Mono.error(new RuntimeException("Invalid callback payload", e));
        }
    }

    /**
     * Emits multiple events to Kafka.
     *
     * @param events The events to emit.
     * @return A Mono signaling completion.
     */
    private Mono<Void> emitEvents(Event... events) {
        log.info("Emitting multiple events: {}", (Object[]) events);
        return Flux.fromArray(events)
                .flatMap(event -> {
                    log.debug("Emitting event: {}", event);
                    return emitEvent(event);
                })
                .then()
                .doOnSuccess(aVoid -> log.info("All events emitted successfully"))
                .doOnError(error -> log.error("Error emitting multiple events: {}",
                        error.getMessage()));
    }

    /**
     * Emits a single event to Kafka.
     *
     * @param event The event to emit.
     * @return A Mono signaling completion.
     */
    private Mono<Void> emitEvent(Event event) {
        String topic = getTopicForEvent(event.getEventType());
        log.info("Emitting event of type '{}' to topic '{}'", event.getEventType(), topic);

        SenderRecord<String, Object, String> record = SenderRecord.create(
                new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, event),
                event.getEventType()
        );

        return kafkaSender.send(Mono.just(record))
                .doOnNext(senderResult -> log.debug("Event emitted with metadata: {}",
                        senderResult.recordMetadata()))
                .then()
                .doOnSuccess(aVoid -> log.info("Event of type '{}' emitted successfully",
                        event.getEventType()))
                .doOnError(error -> log.error("Error emitting event of type '{}': {}",
                        event.getEventType(), error.getMessage()));
    }

    /**
     * Retrieves the Kafka topic based on the event type.
     *
     * @param eventType The type of the event.
     * @return The corresponding Kafka topic.
     */
    private String getTopicForEvent(String eventType) {
        for (EventType type : EventType.values()) {
            if (type.name().equals(eventType)) {
                log.debug("Found topic '{}' for event type '{}'", type.getTopic(), eventType);
                return type.getTopic();
            }
        }
        log.warn("Event type '{}' not found. Using default topic '{}'", eventType,
                KafkaTopics.ORDER_EVENTS);
        // Default topic if the event type is not found
        return KafkaTopics.ORDER_EVENTS;
    }
}
