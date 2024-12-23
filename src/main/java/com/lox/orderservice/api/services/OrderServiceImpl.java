package com.lox.orderservice.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lox.orderservice.api.exceptions.InvalidOrderStatusException;
import com.lox.orderservice.api.exceptions.OrderItemNotFoundException;
import com.lox.orderservice.api.exceptions.OrderNotFoundException;
import com.lox.orderservice.api.kafka.events.Event;
import com.lox.orderservice.api.kafka.events.EventType;
import com.lox.orderservice.api.kafka.events.InitiatePaymentCommand;
import com.lox.orderservice.api.kafka.events.InventoryReservedEvent;
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
import com.lox.orderservice.api.models.responses.ReasonDetail;
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

    // ==============
    // Repositories
    // ==============
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    // ==============
    // Kafka / Mappers
    // ==============
    private final KafkaSender<String, Object> kafkaSender;
    private final OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

    // =======================================
    // 1) Spring-injected default ObjectMapper
    //    (includes JavaTimeModule by default,
    //     provided you have jackson-datatype-jsr310)
    // =======================================
    private final ObjectMapper defaultObjectMapper;

    // =======================================
    // 2) An optional specialized local mapper
    //    if you need different settings
    // =======================================
    private final ObjectMapper specializedObjectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false); // Optional: Ignore unknown properties

//    /**
//     * Initialization method to verify ObjectMapper configurations.
//     */
//    @PostConstruct
//    public void init() {
//        // Verify defaultObjectMapper modules
//        defaultObjectMapper.getRegisteredModules().forEach(module ->
//                log.info("Default ObjectMapper Registered Module: {}", module.getModuleName()));
//
//        // Verify specializedObjectMapper modules
//        specializedObjectMapper.getRegisteredModules().forEach(module ->
//                log.info("Specialized ObjectMapper Registered Module: {}", module.getModuleName()));
//    }

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
                .collect(Collectors.toList());

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
                                createdNotification.setStatus(savedOrder.getStatus().name());
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
                                createdStatus.setStatus(savedOrder.getStatus().name());
                                createdStatus.setCreatedAt(savedOrder.getCreatedAt());
                                createdStatus.setUpdatedAt(savedOrder.getUpdatedAt());
                                createdStatus.setItems(savedOrder.getItems());
                                createdStatus.setTimestamp(Instant.now());

                                // 3) Build RESERVE_INVENTORY_COMMAND
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

                                // Emit the events
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
     * GET ORDER BY ID No messages emitted
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
     * UPDATE ORDER STATUS Emits: - ORDER_UPDATED_NOTIFICATION -> notification.events -
     * ORDER_UPDATED_STATUS       -> order.status.events - If cancelled => ORDER_CANCELLED_STATUS ->
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
     * DELETE ORDER Emits: - ORDER_REMOVED_NOTIFICATION -> notification.events -
     * ORDER_REMOVED_STATUS       -> order.status.events
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
     * LIST ORDERS No events emitted
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
     * HANDLE ORDER STATUS EVENT Possibly emit RESERVE_INVENTORY_COMMAND or
     * INITIATE_PAYMENT_COMMAND
     */
    @Override
    @Transactional
    public Mono<Void> handleOrderStatusEvent(String eventPayload) {
        log.info("Handling order status event, payload={}", eventPayload);

        try {
            // Use the "defaultObjectMapper" that Spring auto-configured
            // (which should have JavaTimeModule for `Instant`)
            InventoryReservedEvent event = defaultObjectMapper.readValue(eventPayload,
                    InventoryReservedEvent.class);
            String eventType = event.getEventType();
            log.info("Parsed eventType={}", eventType);

            return switch (eventType) {
                case "INVENTORY_RESERVED" -> handleInventoryReservedEvent(event);
                default -> {
                    log.debug("No matching event type found, ignoring");
                    yield Mono.empty();
                }
            };
        } catch (com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException e) {
            log.error("Received event with unrecognized properties: {}", e.getMessage());
            return Mono.error(
                    new RuntimeException("Invalid event payload: Unrecognized properties", e));
        } catch (Exception e) {
            log.error("Error handling order status event", e);
            return Mono.error(new RuntimeException("Invalid event payload", e));
        }
    }

    private Mono<Void> handleInventoryReservedEvent(InventoryReservedEvent event) {
        log.info("Handling INVENTORY_RESERVED event for orderId={}", event.getOrderId());

        UUID orderId = event.getOrderId();
        BigDecimal orderTotal = event.getOrderTotal();
        List<InventoryReservedEvent.Item> items = event.getItems();
        List<ReasonDetail> reasons = event.getReasons(); // Access the new field if needed

        return orderRepository.findById(orderId)
                .switchIfEmpty(
                        Mono.error(new OrderNotFoundException("Order not found, id=" + orderId)))
                .flatMap(order -> updateOrderAndItems(order, items, orderTotal, reasons))
                .flatMap(this::emitInitiatePaymentCommand)
                .then()
                .doOnSuccess(v ->
                        log.info("Successfully processed INVENTORY_RESERVED event for orderId={}",
                                orderId)
                )
                .doOnError(e ->
                        log.error("Error processing INVENTORY_RESERVED event for orderId={}",
                                orderId, e)
                );
    }

    private Mono<Order> updateOrderAndItems(Order order, List<InventoryReservedEvent.Item> items,
            BigDecimal orderTotal, List<ReasonDetail> reasons) {
        order.setTotalAmount(orderTotal);
        order.setStatus(OrderStatus.ORDER_RESERVED);
        order.setUpdatedAt(Instant.now());

        // Optionally handle reasons if they affect the order
        if (reasons != null && !reasons.isEmpty()) {
            // Example: Log reasons or update order with reason details
            reasons.forEach(reason ->
                    log.info("Reason for reservation: ProductID={}, ProductName={}, Message={}",
                            reason.getProductId(), reason.getProductName(), reason.getMessage()));
            // You can also store reasons in the order if your Order model supports it
            // e.g., order.setReasons(reasons);
        }

        Mono<Order> saveOrderMono = orderRepository.save(order);

        // Update each OrderItem with new prices
        List<Mono<OrderItem>> updateItemMonos = items.stream()
                .map(item -> orderItemRepository.findByOrderIdAndProductId(order.getOrderId(),
                                item.getProductId())
                        .switchIfEmpty(Mono.error(new OrderItemNotFoundException(
                                "OrderItem not found for productId=" + item.getProductId())))
                        .flatMap(orderItem -> {
                            orderItem.setPrice(item.getUnitPrice());
                            orderItem.setUpdatedAt(Instant.now());
                            return orderItemRepository.save(orderItem);
                        }))
                .collect(Collectors.toList());

        return Flux.merge(updateItemMonos)
                .then(saveOrderMono)
                .doOnNext(updatedOrder -> log.debug(
                        "Updated orderId={} with new totalAmount={}",
                        updatedOrder.getOrderId(),
                        updatedOrder.getTotalAmount()
                ));
    }

    private Mono<Order> emitInitiatePaymentCommand(Order order) {
        InitiatePaymentCommand paymentCommand = InitiatePaymentCommand.builder()
                .eventType("INITIATE_PAYMENT_COMMAND")
                .trackId(order.getTrackId())
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .status(order.getStatus().name())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(order.getItems().stream()
                        .map(oi -> InitiatePaymentCommand.OrderItemEvent.builder()
                                .productId(oi.getProductId())
                                .quantity(oi.getQuantity())
                                .build())
                        .collect(Collectors.toList()))
                .timestamp(Instant.now())
                .build();

        return emitEvent(paymentCommand).thenReturn(order);
    }

    // ====================================================
    // GENERIC EVENT EMISSION
    // ====================================================
    private Mono<Void> emitEvents(Event... events) {
        return Flux.fromArray(events).flatMap(this::emitEvent).then();
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
