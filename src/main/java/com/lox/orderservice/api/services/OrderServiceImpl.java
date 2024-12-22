package com.lox.orderservice.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lox.orderservice.api.exceptions.InvalidOrderStatusException;
import com.lox.orderservice.api.exceptions.OrderNotFoundException;
import com.lox.orderservice.api.kafka.events.Event;
import com.lox.orderservice.api.kafka.events.EventType;
import com.lox.orderservice.api.kafka.events.InitiatePaymentCommand;
import com.lox.orderservice.api.kafka.events.OrderCancelledEvent;
import com.lox.orderservice.api.kafka.events.OrderCreatedEvent;
import com.lox.orderservice.api.kafka.events.OrderRemovedEvent;
import com.lox.orderservice.api.kafka.events.OrderUpdatedEvent;
import com.lox.orderservice.api.kafka.events.ReserveInventoryCommand;
import com.lox.orderservice.api.kafka.topics.KafkaTopics;
import com.lox.orderservice.api.mappers.OrderMapper;
import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.OrderItem;
import com.lox.orderservice.api.models.enums.OrderStatus;
import com.lox.orderservice.api.models.page.OrderPage;
import com.lox.orderservice.api.models.requests.OrderRequest;
import com.lox.orderservice.api.models.responses.OrderResponse;
import com.lox.orderservice.api.models.responses.ReservedItemEvent;
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

    /**
     * CREATE ORDER Emits: - ORDER_CREATED_NOTIFICATION -> notification.events -
     * ORDER_CREATED_STATUS       -> order.status.events - RESERVE_INVENTORY_COMMAND  ->
     * inventory.commands
     */
    @Override
    @Transactional
    public Mono<OrderResponse> createOrder(OrderRequest orderRequest) {
        log.info("Start createOrder for userId={}", orderRequest.getUserId());

        // Generate a trackId for correlation
        UUID trackId = UUID.randomUUID();
        log.debug("Generated trackId={}", trackId);

        // Build initial OrderItems (price=0 just for now)
        List<OrderItem> orderItems = orderRequest.getItems().stream()
                .map(itemReq -> OrderItem.builder()
                        .productId(itemReq.getProductId())
                        .quantity(itemReq.getQuantity())
                        .price(BigDecimal.ZERO)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build())
                .toList();

        // Construct the Order entity
        Order order = Order.builder()
                .trackId(trackId)
                .userId(orderRequest.getUserId())
                .totalAmount(BigDecimal.ZERO)
                .currency(orderRequest.getCurrency())
                .status(OrderStatus.ORDER_CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Save the Order
        return orderRepository.save(order)
                .flatMap(savedOrder -> {
                    log.info("Order saved with orderId={}", savedOrder.getOrderId());

                    // Attach the orderId to each item
                    orderItems.forEach(item -> item.setOrderId(savedOrder.getOrderId()));

                    return orderItemRepository.saveAll(orderItems)
                            .collectList()
                            .flatMap(savedItems -> {
                                savedOrder.setItems(savedItems);

                                // 1) Build ORDER_CREATED_NOTIFICATION
                                OrderCreatedEvent createdNotification = new OrderCreatedEvent();
                                createdNotification.setEventType(
                                        EventType.ORDER_CREATED_NOTIFICATION.name());
                                createdNotification.setTrackId(savedOrder.getTrackId());
                                createdNotification.setOrderId(savedOrder.getOrderId());
                                createdNotification.setUserId(savedOrder.getUserId());
                                createdNotification.setCurrency(savedOrder.getCurrency());
                                createdNotification.setStatus(
                                        String.valueOf(savedOrder.getStatus()));
                                createdNotification.setCreatedAt(savedOrder.getCreatedAt());
                                createdNotification.setUpdatedAt(savedOrder.getUpdatedAt());
                                createdNotification.setItems(
                                        savedOrder.getItems()); // full item objects
                                createdNotification.setTimestamp(Instant.now());

                                // 2) Build ORDER_CREATED_STATUS
                                OrderCreatedEvent createdStatus = new OrderCreatedEvent();
                                createdStatus.setEventType(EventType.ORDER_CREATED_STATUS.name());
                                createdStatus.setTrackId(savedOrder.getTrackId());
                                createdStatus.setOrderId(savedOrder.getOrderId());
                                createdStatus.setUserId(savedOrder.getUserId());
                                createdStatus.setCurrency(savedOrder.getCurrency());
                                createdStatus.setStatus(String.valueOf(savedOrder.getStatus()));
                                createdStatus.setCreatedAt(savedOrder.getCreatedAt());
                                createdStatus.setUpdatedAt(savedOrder.getUpdatedAt());
                                createdStatus.setItems(savedOrder.getItems());
                                createdStatus.setTimestamp(Instant.now());

                                // 3) Build RESERVE_INVENTORY_COMMAND
                                // Map OrderItems -> ReservedItemEvent
                                List<ReservedItemEvent> reservedItemEvents = savedItems.stream()
                                        .map(oi -> ReservedItemEvent.builder()
                                                .productId(oi.getProductId())
                                                .quantity(oi.getQuantity())
                                                .build()
                                        )
                                        .collect(Collectors.toList());

                                ReserveInventoryCommand reserveCmd = new ReserveInventoryCommand();
                                reserveCmd.setEventType(EventType.RESERVE_INVENTORY_COMMAND.name());
                                reserveCmd.setTrackId(savedOrder.getTrackId());
                                reserveCmd.setOrderId(savedOrder.getOrderId());
                                reserveCmd.setItems(reservedItemEvents);
                                reserveCmd.setTimestamp(Instant.now());

                                return emitEvents(createdNotification, createdStatus, reserveCmd)
                                        .then(Mono.fromSupplier(() -> {
                                            // Build final OrderResponse
                                            OrderResponse response = orderMapper.toOrderResponse(
                                                    savedOrder);
                                            log.info("OrderResponse generated: {}", response);
                                            return response;
                                        }));
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error creating order for userId={}", orderRequest.getUserId(), e);
                    return Mono.error(new RuntimeException("Error creating order", e));
                });
    }

    /**
     * GET ORDER BY ID - No messages emitted.
     */
    @Override
    public Mono<OrderResponse> getOrderById(UUID orderId) {
        log.info("Fetching orderId={}", orderId);

        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID=" + orderId)))
                .flatMap(order -> orderItemRepository.findByOrderId(order.getOrderId())
                        .collectList()
                        .map(items -> {
                            order.setItems(items);
                            return order;
                        })
                )
                .map(orderMapper::toOrderResponse);
    }

    /**
     * UPDATE ORDER STATUS - ORDER_UPDATED_NOTIFICATION -> notification.events -
     * ORDER_UPDATED_STATUS       -> order.status.events - if cancelled => ORDER_CANCELLED_STATUS ->
     * order.status.events
     */
    @Override
    @Transactional
    public Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus) {
        log.info("Updating order status, orderId={}, newStatus={}", orderId, newStatus);

        return orderRepository.findById(orderId)
                .switchIfEmpty(
                        Mono.error(new OrderNotFoundException("Order not found, id=" + orderId)))
                .flatMap(order -> {
                    OrderStatus status;
                    try {
                        status = OrderStatus.valueOf(newStatus.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid order status={}", newStatus);
                        return Mono.error(
                                new InvalidOrderStatusException("Invalid status=" + newStatus));
                    }

                    order.setStatus(status);
                    order.setUpdatedAt(Instant.now());

                    return orderRepository.save(order)
                            .flatMap(updatedOrder -> {
                                // Build 2 base events
                                OrderUpdatedEvent updatedNotification = new OrderUpdatedEvent();
                                updatedNotification.setEventType(
                                        EventType.ORDER_UPDATED_NOTIFICATION.name());
                                updatedNotification.setOrderId(updatedOrder.getOrderId());
                                updatedNotification.setTimestamp(Instant.now());

                                OrderUpdatedEvent updatedStatus = new OrderUpdatedEvent();
                                updatedStatus.setEventType(EventType.ORDER_UPDATED_STATUS.name());
                                updatedStatus.setOrderId(updatedOrder.getOrderId());
                                updatedStatus.setTimestamp(Instant.now());

                                if (status == OrderStatus.ORDER_CANCELLED) {
                                    // Also emit ORDER_CANCELLED_STATUS
                                    OrderCancelledEvent cancelledEvent = new OrderCancelledEvent();
                                    cancelledEvent.setEventType(
                                            EventType.ORDER_CANCELLED_STATUS.name());
                                    cancelledEvent.setOrderId(updatedOrder.getOrderId());
                                    cancelledEvent.setReason("Manually cancelled");
                                    cancelledEvent.setTimestamp(Instant.now());

                                    return emitEvents(updatedNotification, updatedStatus,
                                            cancelledEvent)
                                            .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                                } else {
                                    return emitEvents(updatedNotification, updatedStatus)
                                            .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                                }
                            });
                });
    }

    /**
     * DELETE ORDER - ORDER_REMOVED_NOTIFICATION -> notification.events - ORDER_REMOVED_STATUS ->
     * order.status.events
     */
    @Override
    @Transactional
    public Mono<Void> deleteOrder(UUID orderId) {
        log.info("Deleting orderId={}", orderId);

        return orderRepository.findById(orderId)
                .switchIfEmpty(
                        Mono.error(new OrderNotFoundException("Order not found, id=" + orderId)))
                .flatMap(order ->
                        orderRepository.delete(order)
                                .then(emitEvents(buildOrderRemovedNotification(orderId),
                                        buildOrderRemovedStatus(orderId)))
                )
                .then();
    }

    private OrderRemovedEvent buildOrderRemovedNotification(UUID orderId) {
        OrderRemovedEvent evt = new OrderRemovedEvent();
        evt.setEventType(EventType.ORDER_REMOVED_NOTIFICATION.name());
        evt.setOrderId(orderId);
        evt.setTimestamp(Instant.now());
        return evt;
    }

    private OrderRemovedEvent buildOrderRemovedStatus(UUID orderId) {
        OrderRemovedEvent evt = new OrderRemovedEvent();
        evt.setEventType(EventType.ORDER_REMOVED_STATUS.name());
        evt.setOrderId(orderId);
        evt.setTimestamp(Instant.now());
        return evt;
    }

    /**
     * LIST ORDERS - No events emitted.
     */
    @Override
    public Mono<OrderPage> listOrders(String status, UUID userId, Instant startDate,
            Instant endDate, int page, int size) {
        log.info("Listing orders, status={}, userId={}, startDate={}, endDate={}, page={}, size={}",
                status, userId, startDate, endDate, page, size);
        PageRequest pageRequest = PageRequest.of(page, size);

        return orderRepository.findByStatusAndUserIdAndCreatedAtBetween(
                        status, userId, startDate, endDate, pageRequest)
                .flatMap(order ->
                        orderItemRepository.findByOrderId(order.getOrderId())
                                .collectList()
                                .map(items -> {
                                    order.setItems(items);
                                    return order;
                                })
                )
                .map(orderMapper::toOrderResponse)
                .collectList()
                .zipWith(orderRepository.countByStatusAndUserIdAndCreatedAtBetween(
                        status, userId, startDate, endDate))
                .map(tuple -> {
                    List<OrderResponse> orderResponses = tuple.getT1();
                    Long totalCount = tuple.getT2();
                    return OrderPage.builder()
                            .orders(orderResponses)
                            .totalElements(totalCount)
                            .totalPages((int) Math.ceil(totalCount.doubleValue() / size))
                            .currentPage(page)
                            .build();
                });
    }

    /**
     * HANDLE ORDER STATUS EVENT - Possibly emit RESERVE_INVENTORY_COMMAND or
     * INITIATE_PAYMENT_COMMAND
     */
    @Override
    @Transactional
    public Mono<Void> handleOrderStatusEvent(String eventPayload) {
        log.info("Handling order status event, payload={}", eventPayload);
        try {
            JsonNode json = objectMapper.readTree(eventPayload);
            String eventType = json.get("eventType").asText();
            log.info("Parsed eventType={}", eventType);

            // Just an example
            switch (eventType) {
                case "TRIGGER_RESERVE":
                    return emitEvent(buildReserveInventoryCommand(json));
                case "TRIGGER_PAYMENT":
                    return emitEvent(buildInitiatePaymentCommand(json));
                default:
                    log.debug("No matching event type found, ignoring");
                    return Mono.empty();
            }
        } catch (Exception e) {
            log.error("Invalid event payload", e);
            return Mono.error(new RuntimeException("Invalid event payload", e));
        }
    }

    private ReserveInventoryCommand buildReserveInventoryCommand(JsonNode json) {
        ReserveInventoryCommand cmd = new ReserveInventoryCommand();
        cmd.setEventType(EventType.RESERVE_INVENTORY_COMMAND.name());
        cmd.setTimestamp(Instant.now());
        if (json.has("trackId")) {
            cmd.setTrackId(UUID.fromString(json.get("trackId").asText()));
        }
        if (json.has("orderId")) {
            cmd.setOrderId(UUID.fromString(json.get("orderId").asText()));
        }
        // If "items" is present, parse them into List<ReservedItemEvent>:
        // ...
        return cmd;
    }

    private InitiatePaymentCommand buildInitiatePaymentCommand(JsonNode json) {
        InitiatePaymentCommand cmd = new InitiatePaymentCommand();
        cmd.setEventType(EventType.INITIATE_PAYMENT_COMMAND.name());
        cmd.setTimestamp(Instant.now());
        if (json.has("trackId")) {
            cmd.setTrackId(UUID.fromString(json.get("trackId").asText()));
        }
        if (json.has("orderId")) {
            cmd.setOrderId(UUID.fromString(json.get("orderId").asText()));
        }
        return cmd;
    }

    // ==================================
    // GENERIC EVENT EMISSION
    // ==================================
    private Mono<Void> emitEvents(Event... events) {
        return Flux.fromArray(events)
                .flatMap(this::emitEvent)
                .then();
    }

    private Mono<Void> emitEvent(Event event) {
        String topic = getTopicForEvent(event.getEventType());
        SenderRecord<String, Object, String> record = SenderRecord.create(
                new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, event),
                event.getEventType()
        );

        log.info("Emitting event={}, topic={}", event.getEventType(), topic);
        return kafkaSender.send(Mono.just(record))
                .doOnNext(result ->
                        log.info("Successfully sent eventType={} to topic={}", event.getEventType(),
                                topic)
                )
                .then();
    }

    private String getTopicForEvent(String eventType) {
        for (EventType type : EventType.values()) {
            if (type.name().equals(eventType)) {
                return type.getTopic();
            }
        }
        return KafkaTopics.ORDER_STATUS_EVENTS_TOPIC; // fallback if not found
    }
}
