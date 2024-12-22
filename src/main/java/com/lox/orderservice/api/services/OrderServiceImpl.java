// OrderServiceImpl.java
package com.lox.orderservice.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.lox.orderservice.api.exceptions.InvalidOrderStatusException;
import com.lox.orderservice.api.exceptions.OrderNotFoundException;
import com.lox.orderservice.api.kafka.events.Event;
import com.lox.orderservice.api.kafka.events.EventType;
import com.lox.orderservice.api.kafka.events.InitiatePaymentCommand;
import com.lox.orderservice.api.kafka.events.OrderCancelledEvent;
import com.lox.orderservice.api.kafka.events.OrderCompletedEvent;
import com.lox.orderservice.api.kafka.events.OrderCreatedEvent;
import com.lox.orderservice.api.kafka.events.OrderRemovedEvent;
import com.lox.orderservice.api.kafka.events.OrderUpdatedEvent;
import com.lox.orderservice.api.kafka.events.ReleaseInventoryCommand;
import com.lox.orderservice.api.kafka.topics.KafkaTopics;
import com.lox.orderservice.api.mappers.OrderMapper;
import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.OrderItem;
import com.lox.orderservice.api.models.OrderPage;
import com.lox.orderservice.api.models.OrderStatus;
import com.lox.orderservice.api.models.dto.InventoryReservedItemPayload;
import com.lox.orderservice.api.models.dto.OrderRequest;
import com.lox.orderservice.api.models.dto.OrderResponse;
import com.lox.orderservice.api.models.dto.ReservedItemEvent;
import com.lox.orderservice.api.repositories.r2dbc.OrderItemRepository;
import com.lox.orderservice.api.repositories.r2dbc.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        // Start createOrder method
        log.info("Start createOrder for userId: {}", orderRequest.getUserId()); // Logging info

        // Also log at debug level for detailed info
        log.debug("Creating order for userId: {}", orderRequest.getUserId()); // Existing debug log

        BigDecimal totalAmount = BigDecimal.ZERO;

        // Generate trackId in the service layer
        UUID trackId = UUID.randomUUID();
        log.info("Generated trackId: {}", trackId); // Info log for trackId

        // Create OrderItems without prices
        List<OrderItem> orderItems = orderRequest.getItems().stream()
                .map(itemRequest -> OrderItem.builder()
                        .productId(itemRequest.getProductId())
                        .quantity(itemRequest.getQuantity())
                        .price(BigDecimal.ZERO) // Price managed internally
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build())
                .toList();

        // Build the Order entity
        Order order = Order.builder()
                .userId(orderRequest.getUserId())
                .trackId(trackId)
                .totalAmount(totalAmount)
                .currency(orderRequest.getCurrency())
                .status(OrderStatus.ORDER_CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Save the order to the database
        return orderRepository.save(order)
                .flatMap(savedOrder -> {
                    log.info("Order saved with orderId: {}",
                            savedOrder.getOrderId()); // Info log after saving order
                    log.debug("Order saved with orderId: {}",
                            savedOrder.getOrderId()); // Existing debug log

                    // Assign orderId to each OrderItem
                    orderItems.forEach(item -> item.setOrderId(savedOrder.getOrderId()));

                    // Save OrderItems
                    return orderItemRepository.saveAll(orderItems)
                            .collectList()
                            .flatMap(savedOrderItems -> {
                                log.info("OrderItems saved: {}",
                                        savedOrderItems); // Info log after saving items
                                log.debug("OrderItems saved: {}",
                                        savedOrderItems); // Existing debug log

                                savedOrder.setItems(savedOrderItems);

                                // Emit the OrderCreatedEvent
                                return emitEvent(OrderCreatedEvent.fromOrder(savedOrder))
                                        .doOnSuccess(aVoid ->
                                                log.info(
                                                        "OrderCreatedEvent emitted for orderId: {}",
                                                        savedOrder.getOrderId())
                                        ) // Info log on successful event emission
                                        .then(Mono.defer(() -> {
                                            OrderResponse response = orderMapper.toOrderResponse(
                                                    savedOrder);
                                            log.info("OrderResponse generated: {}",
                                                    response); // Info log for the response
                                            log.debug("OrderResponse generated: {}",
                                                    response); // Existing debug log
                                            return Mono.just(response);
                                        }));
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error creating order for userId: {}", orderRequest.getUserId(),
                            e); // Error log
                    return Mono.error(new RuntimeException("Error creating order", e));
                });
    }

    @Override
    public Mono<OrderResponse> getOrderById(UUID orderId) {
        log.info("Fetching order by ID: {}", orderId); // Info log at the start

        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId))
                )
                .flatMap(order -> orderItemRepository.findByOrderId(orderId)
                        .collectList()
                        .map(items -> {
                            order.setItems(items);
                            return order;
                        })
                )
                .map(orderMapper::toOrderResponse)
                .doOnNext(orderResponse ->
                                log.info("Successfully retrieved OrderResponse: {}", orderResponse)
                        // Info log on success
                );
    }

    @Override
    @Transactional
    public Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus) {
        log.info("Updating order status for orderId: {} to {}", orderId,
                newStatus); // Info log at the start

        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId))
                )
                .flatMap(existingOrder -> {
                    OrderStatus updatedStatus;
                    try {
                        updatedStatus = OrderStatus.valueOf(newStatus.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid order status: {}", newStatus); // Log error
                        return Mono.error(
                                new InvalidOrderStatusException(
                                        "Invalid order status: " + newStatus)
                        );
                    }
                    existingOrder.setStatus(updatedStatus);
                    existingOrder.setUpdatedAt(Instant.now());
                    return orderRepository.save(existingOrder)
                            .flatMap(updatedOrder -> {
                                log.info("Order status updated to {}",
                                        updatedStatus); // Info log after updating
                                OrderUpdatedEvent orderUpdatedEvent = OrderUpdatedEvent.fromOrder(
                                        updatedOrder);

                                // If order is cancelled, emit additional event
                                if (updatedStatus == OrderStatus.ORDER_CANCELLED) {
                                    // We assume a reason from some source:
                                    String reason = "Cancellation reason";
                                    OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.builder()
                                            .eventType(EventType.ORDER_CANCELLED.name())
                                            .orderId(orderId)
                                            .reason(reason)
                                            .timestamp(Instant.now())
                                            .build();
                                    return emitEvents(orderUpdatedEvent, orderCancelledEvent)
                                            .doOnSuccess(aVoid ->
                                                    log.info(
                                                            "Emitted OrderUpdatedEvent and OrderCancelledEvent for orderId: {}",
                                                            orderId)
                                            )
                                            .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                                }

                                // Otherwise, just emit the updated event
                                return emitEvent(orderUpdatedEvent)
                                        .doOnSuccess(aVoid ->
                                                log.info(
                                                        "Emitted OrderUpdatedEvent for orderId: {}",
                                                        orderId)
                                        )
                                        .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                            });
                });
    }

    @Override
    @Transactional
    public Mono<Void> deleteOrder(UUID orderId) {
        log.info("Deleting order with ID: {}", orderId); // Info log at the start

        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> orderRepository.delete(order)
                        .doOnSuccess(aVoid ->
                                        log.info("Order deleted from the database. orderId: {}", orderId)
                                // Info log after deletion
                        )
                        .then(emitEvent(OrderRemovedEvent.fromOrder(order)))
                        .doOnSuccess(aVoid ->
                                        log.info("OrderRemovedEvent emitted for orderId: {}", orderId)
                                // Info log on event emission
                        )
                )
                .then();
    }

    @Override
    public Mono<OrderPage> listOrders(String status, UUID userId, Instant startDate,
            Instant endDate, int page, int size) {
        log.info(
                "Listing orders - status: {}, userId: {}, startDate: {}, endDate: {}, page: {}, size: {}",
                status, userId, startDate, endDate, page, size); // Info log with query details

        PageRequest pageRequest = PageRequest.of(page, size);
        return orderRepository.findByStatusAndUserIdAndCreatedAtBetween(status, userId, startDate,
                        endDate, pageRequest)
                .flatMap(order -> orderItemRepository.findByOrderId(order.getOrderId())
                        .collectList()
                        .map(items -> {
                            order.setItems(items);
                            return order;
                        })
                )
                .map(orderMapper::toOrderResponse)
                .collectList()
                .zipWith(orderRepository.countByStatusAndUserIdAndCreatedAtBetween(status, userId,
                        startDate, endDate))
                .map(tuple -> {
                    log.info("Orders fetched: {}, Total count: {}", tuple.getT1().size(),
                            tuple.getT2()); // Info log
                    return OrderPage.builder()
                            .orders(tuple.getT1())
                            .totalElements(tuple.getT2())
                            .totalPages((int) Math.ceil((double) tuple.getT2() / size))
                            .currentPage(page)
                            .build();
                });
    }

    @Override
    @Transactional
    public Mono<Void> handleOrderStatusEvent(String eventPayload) {
        log.info("Handling order status event with payload: {}",
                eventPayload); // Info log for the incoming payload

        try {
            JsonNode jsonNode = objectMapper.readTree(eventPayload);
            String eventType = jsonNode.get("eventType").asText();
            log.info("Parsed eventType: {}", eventType); // Info log after parsing eventType

            switch (eventType) {
                case "INVENTORY_RESERVED":
                    return handleInventoryReservedEvent(jsonNode);
                case "INVENTORY_RESERVE_FAILED":
                    return handleInventoryReserveFailedEvent(jsonNode);
                case "PAYMENT_SUCCEEDED":
                    return handlePaymentSucceededEvent(jsonNode);
                case "PAYMENT_FAILED":
                    return handlePaymentFailedEvent(jsonNode);
                default:
                    log.info(
                            "No matching event type found, ignoring."); // Info log for unknown event
                    return Mono.empty();
            }
        } catch (Exception e) {
            log.error("Invalid event payload", e); // Error log
            return Mono.error(new RuntimeException("Invalid event payload", e));
        }
    }

    private Mono<Void> handleInventoryReservedEvent(JsonNode jsonNode) {
        log.info("Handling INVENTORY_RESERVED event");

        // 1) Extract the main fields from the incoming JSON event
        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        UUID trackId = UUID.fromString(jsonNode.get("trackId").asText());
        double orderTotalFromEvent = jsonNode.get("orderTotal").asDouble();

        // Extract the items array
        ArrayNode itemsNode = (ArrayNode) jsonNode.get("items");
        List<InventoryReservedItemPayload> reservedItems = new ArrayList<>();

        // Map each item from the JSON array to a Java object
        for (JsonNode itemNode : itemsNode) {
            reservedItems.add(
                    InventoryReservedItemPayload.builder()
                            .productId(UUID.fromString(itemNode.get("productId").asText()))
                            .quantity(itemNode.get("quantity").asInt())
                            .unitPrice(itemNode.get("unitPrice").asDouble())   // Price for one unit
                            .totalPrice(
                                    itemNode.get("totalPrice").asDouble()) // unitPrice * quantity
                            .build()
            );
        }

        // 2) Update the order items and the order in the database
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId))
                )
                .flatMap(order -> {
                    // Build a Flux to update each order item
                    Flux<OrderItem> updatedItemsFlux = Flux.fromIterable(reservedItems)
                            .flatMap(reservedItem ->
                                    orderItemRepository.findByOrderIdAndProductId(orderId,
                                                    reservedItem.getProductId())
                                            .switchIfEmpty(Mono.error(
                                                    new RuntimeException(
                                                            "orderItem not found - orderId: "
                                                                    + orderId
                                                                    + ", productId: "
                                                                    + reservedItem.getProductId())
                                            ))
                                            .flatMap(orderItem -> {
                                                // Update the total price in the order item
                                                orderItem.setPrice(BigDecimal.valueOf(
                                                        reservedItem.getTotalPrice()));
                                                orderItem.setUpdatedAt(Instant.now());
                                                return orderItemRepository.save(orderItem);
                                            })
                            );

                    return updatedItemsFlux.collectList()
                            .flatMap(updatedItems -> {
                                log.info("Updated items: {}", updatedItems);

                                // Update the order's status and total amount
                                order.setStatus(OrderStatus.ORDER_RESERVED);
                                order.setUpdatedAt(Instant.now());
                                order.setTotalAmount(BigDecimal.valueOf(orderTotalFromEvent));

                                // Persist the updated order
                                return orderRepository.save(order)
                                        .flatMap(savedOrder -> {
                                            log.info(
                                                    "Order reserved, initiating payment command for orderId: {}",
                                                    orderId);

                                            // 3) Build and emit the InitiatePaymentCommand
                                            List<ReservedItemEvent> reservedItemEvents = reservedItems.stream()
                                                    .map(r -> ReservedItemEvent.builder()
                                                            .productId(r.getProductId())
                                                            .quantity(r.getQuantity())
                                                            .unitPrice(r.getUnitPrice())
                                                            .totalPrice(r.getTotalPrice())
                                                            .build())
                                                    .toList();

                                            InitiatePaymentCommand initiatePaymentCommand = InitiatePaymentCommand.builder()
                                                    .eventType(
                                                            EventType.INITIATE_PAYMENT_COMMAND.name())
                                                    .trackId(trackId)
                                                    .orderId(orderId)
                                                    .items(reservedItemEvents)
                                                    .orderTotal(orderTotalFromEvent)
                                                    .timestamp(Instant.now())
                                                    .build();

                                            // Send the command to the 'payment.commands' topic
                                            return emitEvent(initiatePaymentCommand)
                                                    .doOnSuccess(aVoid ->
                                                            log.info(
                                                                    "InitiatePaymentCommand emitted for orderId: {}",
                                                                    orderId)
                                                    );
                                        });
                            });
                })
                .then();
    }


    private Mono<Void> handleInventoryReserveFailedEvent(JsonNode jsonNode) {
        log.info("Handling INVENTORY_RESERVE_FAILED event"); // Info log

        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "Unknown";

        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId))
                )
                .flatMap(order -> {
                    order.setStatus(OrderStatus.ORDER_CANCELLED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(savedOrder -> {
                                log.info(
                                        "Order cancelled due to inventory reserve failure, orderId: {}",
                                        orderId); // Info
                                OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.builder()
                                        .eventType(EventType.ORDER_CANCELLED.name())
                                        .orderId(orderId)
                                        .reason(reason)
                                        .timestamp(Instant.now())
                                        .build();
                                return emitEvent(orderCancelledEvent)
                                        .doOnSuccess(aVoid ->
                                                log.info(
                                                        "OrderCancelledEvent emitted for orderId: {}",
                                                        orderId) // Info
                                        );
                            });
                })
                .then();
    }

    private Mono<Void> handlePaymentSucceededEvent(JsonNode jsonNode) {
        log.info("Handling PAYMENT_SUCCEEDED event"); // Info log

        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId))
                )
                .flatMap(order -> {
                    order.setStatus(OrderStatus.ORDER_COMPLETED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(savedOrder -> {
                                log.info("Order completed successfully, orderId: {}",
                                        orderId); // Info
                                OrderCompletedEvent orderCompletedEvent = OrderCompletedEvent.builder()
                                        .eventType(EventType.ORDER_COMPLETED.name())
                                        .orderId(orderId)
                                        .timestamp(Instant.now())
                                        .build();
                                return emitEvent(orderCompletedEvent)
                                        .doOnSuccess(aVoid ->
                                                log.info(
                                                        "OrderCompletedEvent emitted for orderId: {}",
                                                        orderId) // Info
                                        );
                            });
                })
                .then();
    }

    private Mono<Void> handlePaymentFailedEvent(JsonNode jsonNode) {
        log.info("Handling PAYMENT_FAILED event"); // Info log

        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "Unknown";

        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId))
                )
                .flatMap(order -> {
                    order.setStatus(OrderStatus.ORDER_CANCELLED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(savedOrder -> {
                                log.info("Order cancelled due to payment failure, orderId: {}",
                                        orderId); // Info
                                OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.builder()
                                        .eventType(EventType.ORDER_CANCELLED.name())
                                        .orderId(orderId)
                                        .reason(reason)
                                        .timestamp(Instant.now())
                                        .build();

                                // In a real scenario, you'd handle multiple OrderItems. This is just a sample.
                                ReleaseInventoryCommand releaseInventoryCommand = ReleaseInventoryCommand.builder()
                                        .eventType(EventType.RELEASE_INVENTORY_COMMAND.name())
                                        .orderId(orderId)
                                        .productId(savedOrder.getItems().getFirst().getProductId())
                                        .quantity(savedOrder.getItems().getFirst().getQuantity())
                                        .timestamp(Instant.now())
                                        .build();

                                return emitEvents(orderCancelledEvent, releaseInventoryCommand)
                                        .doOnSuccess(aVoid ->
                                                log.info(
                                                        "Emitted OrderCancelledEvent and ReleaseInventoryCommand for orderId: {}",
                                                        orderId) // Info
                                        );
                            });
                })
                .then();
    }

    private Mono<Void> emitEvents(Event... events) {
        // Emit multiple events
        return Flux.fromArray(events)
                .flatMap(this::emitEvent)
                .then();
    }

    private Mono<Void> emitEvent(Event event) {
        // Determine topic and send event
        String topic = getTopicForEvent(event.getEventType());
        SenderRecord<String, Object, String> record = SenderRecord.create(
                new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, event),
                event.getEventType()
        );
        log.info("Emitting event: {} to topic: {}", event.getEventType(),
                topic); // Info log before sending
        return kafkaSender.send(Mono.just(record))
                .doOnNext(result ->
                        log.info("Successfully sent eventType: {} to topic: {}",
                                event.getEventType(), topic) // Info
                )
                .then();
    }

    private String getTopicForEvent(String eventType) {
        // Map the event type to the topic
        for (EventType type : EventType.values()) {
            if (type.name().equals(eventType)) {
                return type.getTopic();
            }
        }
        return KafkaTopics.ORDER_EVENTS;
    }
}
