package com.lox.orderservice.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lox.orderservice.api.exceptions.InvalidOrderStatusException;
import com.lox.orderservice.api.exceptions.OrderItemNotFoundException;
import com.lox.orderservice.api.exceptions.OrderNotFoundException;
import com.lox.orderservice.api.kafka.events.CompleteInventoryCommand;
import com.lox.orderservice.api.kafka.events.Event;
import com.lox.orderservice.api.kafka.events.EventType;
import com.lox.orderservice.api.kafka.events.InitiatePaymentCommand;
import com.lox.orderservice.api.kafka.events.InventoryReservedEvent;
import com.lox.orderservice.api.kafka.events.OrderCancelledEvent;
import com.lox.orderservice.api.kafka.events.OrderCompletedEvent;
import com.lox.orderservice.api.kafka.events.OrderCreatedEvent;
import com.lox.orderservice.api.kafka.events.OrderRemovedEvent;
import com.lox.orderservice.api.kafka.events.OrderUpdatedEvent;
import com.lox.orderservice.api.kafka.events.ReleaseInventoryCommand;
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
import java.util.ArrayList;
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
    private final OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);
    private final ObjectMapper defaultObjectMapper;

    @Override
    @Transactional
    public Mono<OrderResponse> createOrder(OrderRequest orderRequest) {
        UUID trackId = UUID.randomUUID();
        List<OrderItem> orderItems = orderRequest.getItems().stream()
                .map(itemReq -> OrderItem.builder()
                        .productId(itemReq.getProductId())
                        .quantity(itemReq.getQuantity())
                        .price(BigDecimal.ZERO)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build())
                .collect(Collectors.toList());

        Order order = Order.builder()
                .trackId(trackId)
                .userId(orderRequest.getUserId())
                .totalAmount(BigDecimal.ZERO)
                .currency(orderRequest.getCurrency())
                .status(OrderStatus.ORDER_CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return orderRepository.save(order)
                .flatMap(savedOrder -> {
                    orderItems.forEach(item -> item.setOrderId(savedOrder.getOrderId()));
                    return orderItemRepository.saveAll(orderItems)
                            .collectList()
                            .flatMap(savedItems -> {
                                savedOrder.setItems(savedItems);

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
                                createdNotification.setItems(savedOrder.getItems());
                                createdNotification.setTimestamp(Instant.now());

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
                                            OrderResponse response = orderMapper.toOrderResponse(
                                                    savedOrder);
                                            return response;
                                        }));
                            });
                })
                .onErrorResume(e -> Mono.error(new RuntimeException("Error creating order", e)));
    }

    @Override
    public Mono<OrderResponse> getOrderById(UUID orderId) {
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

    @Override
    @Transactional
    public Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(
                        Mono.error(new OrderNotFoundException("Order not found, id=" + orderId)))
                .flatMap(order -> {
                    OrderStatus status;
                    try {
                        status = OrderStatus.valueOf(newStatus.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return Mono.error(
                                new InvalidOrderStatusException("Invalid status=" + newStatus));
                    }
                    order.setStatus(status);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(updatedOrder -> {
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

    @Override
    @Transactional
    public Mono<Void> deleteOrder(UUID orderId) {
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

    @Override
    public Mono<OrderPage> listOrders(String status, UUID userId, Instant startDate,
            Instant endDate, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return orderRepository.findByStatusAndUserIdAndCreatedAtBetween(status, userId, startDate,
                        endDate, pageRequest)
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
                .zipWith(orderRepository.countByStatusAndUserIdAndCreatedAtBetween(status, userId,
                        startDate, endDate))
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

    @Transactional
    public Mono<Void> handleOrderStatusEvent(String eventPayload) {
        log.info("handleOrderStatusEvent() -> Received payload: {}", eventPayload);

        try {
            InventoryReservedEvent event = defaultObjectMapper.readValue(eventPayload, InventoryReservedEvent.class);
            String eventType = event.getEventType();
            log.info("handleOrderStatusEvent() -> Parsed eventType={}, orderId={}, trackId={}",
                    eventType, event.getOrderId(), event.getTrackId());

            if ("INVENTORY_RESERVED_STATUS".equals(eventType)) {
                return handleInventoryReservedEvent(event);
            } else if ("INVENTORY_RESERVE_FAILED_STATUS".equals(eventType)) {
                return handleInventoryReserveFailedEvent(eventPayload);
            } else {
                log.warn("handleOrderStatusEvent() -> Unhandled eventType={}, ignoring...", eventType);
                return Mono.empty();
            }
        } catch (Exception e) {
            log.error("handleOrderStatusEvent() -> ERROR parsing payload: {}", e.getMessage());
            return Mono.error(new RuntimeException("Invalid event payload", e));
        }
    }


    public Mono<Void> handleInventoryReservedEvent(InventoryReservedEvent event) {
        UUID orderId = event.getOrderId();
        BigDecimal orderTotal = event.getOrderTotal();
        List<InventoryReservedEvent.Item> items = event.getItems();

        log.info("handleInventoryReservedEvent() -> Start. orderId={}, orderTotal={}, trackId={}",
                orderId, orderTotal, event.getTrackId());

        return orderRepository.findById(orderId)
                .switchIfEmpty(
                        Mono.error(new OrderNotFoundException("Order not found, id=" + orderId)))
                .flatMap(existingOrder -> {
                    log.info("handleInventoryReservedEvent() -> Found order with status={}",
                            existingOrder.getStatus());
                    if (existingOrder.getStatus() == OrderStatus.ORDER_COMPLETED
                            || existingOrder.getStatus() == OrderStatus.ORDER_CANCELLED) {
                        log.warn(
                                "handleInventoryReservedEvent() -> Order is in final status ({}), ignoring...",
                                existingOrder.getStatus());
                        return Mono.empty();
                    }
                    if (existingOrder.getStatus() == OrderStatus.ORDER_RESERVED) {
                        log.warn(
                                "handleInventoryReservedEvent() -> Order is already ORDER_RESERVED, ignoring repeated event");
                        return Mono.empty();
                    }
                    log.info(
                            "handleInventoryReservedEvent() -> Updating orderId={} to ORDER_RESERVED with totalAmount={}",
                            orderId, orderTotal);
                    existingOrder.setStatus(OrderStatus.ORDER_RESERVED);
                    existingOrder.setTotalAmount(orderTotal);
                    existingOrder.setUpdatedAt(Instant.now());
                    return orderRepository.save(existingOrder);
                })
                .flatMapMany(updatedOrder -> {
                    if (updatedOrder.getStatus() != OrderStatus.ORDER_RESERVED) {
                        log.info(
                                "handleInventoryReservedEvent() -> Order status is not ORDER_RESERVED after save, skipping item updates");
                        return Flux.empty();
                    }
                    log.info(
                            "handleInventoryReservedEvent() -> Updating items price for orderId={}",
                            updatedOrder.getOrderId());
                    List<Mono<OrderItem>> updateItemMonos = items.stream()
                            .map(item -> orderItemRepository.findByOrderIdAndProductId(
                                            updatedOrder.getOrderId(), item.getProductId())
                                    .switchIfEmpty(Mono.error(new OrderItemNotFoundException(
                                            "OrderItem not found for productId="
                                                    + item.getProductId())))
                                    .flatMap(existingItem -> {
                                        log.debug(
                                                "handleInventoryReservedEvent() -> Setting price={} for productId={}",
                                                item.getUnitPrice(), item.getProductId());
                                        existingItem.setPrice(item.getUnitPrice());
                                        existingItem.setUpdatedAt(Instant.now());
                                        return orderItemRepository.save(existingItem);
                                    })
                            )
                            .toList();
                    return Flux.merge(updateItemMonos).thenMany(Mono.just(updatedOrder));
                })
                .flatMap(updatedOrder -> {
                    if (updatedOrder.getStatus() != OrderStatus.ORDER_RESERVED) {
                        log.warn(
                                "handleInventoryReservedEvent() -> Final check: orderId={} is not ORDER_RESERVED, ignoring Payment Command emission",
                                updatedOrder.getOrderId());
                        return Mono.empty();
                    }
                    log.info(
                            "handleInventoryReservedEvent() -> Emitting INITIATE_PAYMENT_COMMAND for orderId={}",
                            updatedOrder.getOrderId());
                    return orderItemRepository.findByOrderId(updatedOrder.getOrderId())
                            .collectList()
                            .flatMap(dbItems -> {
                                updatedOrder.setItems(dbItems);
                                return emitInitiatePaymentCommand(updatedOrder);
                            });
                })
                .then()
                .doOnSuccess(v -> log.info(
                        "handleInventoryReservedEvent() -> Completed successfully for orderId={}",
                        orderId))
                .doOnError(e -> log.error(
                        "handleInventoryReservedEvent() -> Error for orderId={}, error={}", orderId,
                        e.getMessage()));
    }

    @Transactional
    public Mono<Void> handlePaymentStatusEvent(String eventPayload) {
        log.info("handlePaymentStatusEvent() -> Received payload: {}", eventPayload);
        try {
            JsonNode root = defaultObjectMapper.readTree(eventPayload);
            String eventType = root.has("eventType") ? root.get("eventType").asText() : null;
            String trackIdStr = root.has("trackId") ? root.get("trackId").asText() : null;
            String message = root.has("message") ? root.get("message").asText() : null;
            String status = root.has("status") ? root.get("status").asText() : null;

            log.info(
                    "handlePaymentStatusEvent() -> eventType={}, trackId={}, status={}, message={}",
                    eventType, trackIdStr, status, message);

            if ("PAYMENT_FAILED_ORDERS".equalsIgnoreCase(eventType)) {
                return handlePaymentFailedOrders(trackIdStr, message)
                        .doOnSuccess(v -> log.info(
                                "handlePaymentStatusEvent() -> FINISHED PAYMENT_FAILED_ORDERS for trackId={}",
                                trackIdStr))
                        .doOnError(e -> log.error(
                                "handlePaymentStatusEvent() -> ERROR PAYMENT_FAILED_ORDERS for trackId={}, error={}",
                                trackIdStr, e.getMessage()));
            } else if ("PAYMENT_SUCCESS_ORDERS".equalsIgnoreCase(eventType)) {
                return handlePaymentSuccessOrders(trackIdStr)
                        .doOnSuccess(v -> log.info(
                                "handlePaymentStatusEvent() -> FINISHED PAYMENT_SUCCESS_ORDERS for trackId={}",
                                trackIdStr))
                        .doOnError(e -> log.error(
                                "handlePaymentStatusEvent() -> ERROR PAYMENT_SUCCESS_ORDERS for trackId={}, error={}",
                                trackIdStr, e.getMessage()));
            } else {
                log.warn("handlePaymentStatusEvent() -> Unhandled eventType={}, ignoring...",
                        eventType);
                return Mono.empty();
            }
        } catch (Exception e) {
            log.error("handlePaymentStatusEvent() -> ERROR parsing payload: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> handlePaymentFailedOrders(String trackIdStr, String cancellationReason) {
        log.info("handlePaymentFailedOrders() -> trackId={}, reason={}", trackIdStr,
                cancellationReason);
        UUID trackId = UUID.fromString(trackIdStr);

        return orderRepository.findByTrackId(trackId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found for trackId=" + trackId)))
                .flatMap(order -> {
                    log.info("handlePaymentFailedOrders() -> Found orderId={}, current status={}",
                            order.getOrderId(), order.getStatus());
                    if (order.getStatus() == OrderStatus.ORDER_COMPLETED
                            || order.getStatus() == OrderStatus.ORDER_CANCELLED) {
                        log.warn(
                                "handlePaymentFailedOrders() -> Order is already in final status={}, ignoring event",
                                order.getStatus());
                        return Mono.empty();
                    }
                    order.setStatus(OrderStatus.ORDER_CANCELLED);
                    order.setCancellationReason(cancellationReason);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order);
                })
                .flatMap(updatedOrder -> {
                    if (updatedOrder.getStatus() != OrderStatus.ORDER_CANCELLED) {
                        log.info(
                                "handlePaymentFailedOrders() -> Order not CANCELLED after update, skipping subsequent events");
                        return Mono.empty();
                    }
                    log.info(
                            "handlePaymentFailedOrders() -> Emitting RELEASE_INVENTORY_COMMAND, ORDER_CANCELLED_NOTIFICATION, ORDER_CANCELLED_STATUS for orderId={}",
                            updatedOrder.getOrderId());
                    return orderItemRepository.findByOrderId(updatedOrder.getOrderId())
                            .collectList()
                            .flatMap(items -> {
                                List<ReservedItemEvent> reservedItems = items.stream()
                                        .map(oi -> ReservedItemEvent.builder()
                                                .productId(oi.getProductId())
                                                .quantity(oi.getQuantity())
                                                .build())
                                        .collect(Collectors.toList());

                                ReleaseInventoryCommand releaseCmd = ReleaseInventoryCommand.builder()
                                        .eventType(EventType.RELEASE_INVENTORY_COMMAND.name())
                                        .trackId(updatedOrder.getTrackId())
                                        .orderId(updatedOrder.getOrderId())
                                        .items(reservedItems)
                                        .timestamp(Instant.now())
                                        .build();

                                OrderCancelledEvent cancelledNotification = OrderCancelledEvent.builder()
                                        .eventType(EventType.ORDER_CANCELLED_NOTIFICATION.name())
                                        .orderId(updatedOrder.getOrderId())
                                        .reason(cancellationReason)
                                        .timestamp(Instant.now())
                                        .build();

                                OrderCancelledEvent cancelledStatus = OrderCancelledEvent.builder()
                                        .eventType(EventType.ORDER_CANCELLED_STATUS.name())
                                        .orderId(updatedOrder.getOrderId())
                                        .reason(cancellationReason)
                                        .timestamp(Instant.now())
                                        .build();

                                return emitEvents(releaseCmd, cancelledNotification,
                                        cancelledStatus);
                            });
                })
                .then()
                .doOnSuccess(
                        v -> log.info("handlePaymentFailedOrders() -> Completed for trackId={}",
                                trackIdStr))
                .doOnError(e -> log.error(
                        "handlePaymentFailedOrders() -> Error for trackId={}, error={}", trackIdStr,
                        e.getMessage()));
    }

    private Mono<Void> handlePaymentSuccessOrders(String trackIdStr) {
        log.info("handlePaymentSuccessOrders() -> trackId={}", trackIdStr);
        UUID trackId = UUID.fromString(trackIdStr);

        return orderRepository.findByTrackId(trackId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found for trackId=" + trackId)))
                .flatMap(order -> {
                    log.info("handlePaymentSuccessOrders() -> Found orderId={}, current status={}",
                            order.getOrderId(), order.getStatus());
                    if (order.getStatus() == OrderStatus.ORDER_COMPLETED
                            || order.getStatus() == OrderStatus.ORDER_CANCELLED) {
                        log.warn(
                                "handlePaymentSuccessOrders() -> Order is already in final status={}, ignoring event",
                                order.getStatus());
                        return Mono.empty();
                    }
                    order.setStatus(OrderStatus.ORDER_COMPLETED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order);
                })
                .flatMap(updatedOrder -> {
                    if (updatedOrder.getStatus() != OrderStatus.ORDER_COMPLETED) {
                        log.info(
                                "handlePaymentSuccessOrders() -> Order not COMPLETED after update, skipping subsequent events");
                        return Mono.empty();
                    }
                    log.info(
                            "handlePaymentSuccessOrders() -> Emitting ORDER_COMPLETED_NOTIFICATION, ORDER_COMPLETED_STATUS, COMPLETE_INVENTORY_COMMAND for orderId={}",
                            updatedOrder.getOrderId());
                    return orderItemRepository.findByOrderId(updatedOrder.getOrderId())
                            .collectList()
                            .flatMap(items -> {
                                OrderCompletedEvent completedNotification = OrderCompletedEvent.builder()
                                        .eventType(EventType.ORDER_COMPLETED_NOTIFICATION.name())
                                        .orderId(updatedOrder.getOrderId())
                                        .timestamp(Instant.now())
                                        .build();

                                OrderCompletedEvent completedStatus = OrderCompletedEvent.builder()
                                        .eventType(EventType.ORDER_COMPLETED_STATUS.name())
                                        .orderId(updatedOrder.getOrderId())
                                        .timestamp(Instant.now())
                                        .build();

                                List<ReservedItemEvent> reservedItems = items.stream()
                                        .map(oi -> ReservedItemEvent.builder()
                                                .productId(oi.getProductId())
                                                .quantity(oi.getQuantity())
                                                .build())
                                        .collect(Collectors.toList());

                                CompleteInventoryCommand completeCmd = CompleteInventoryCommand.builder()
                                        .eventType(EventType.COMPLETE_INVENTORY_COMMAND.name())
                                        .trackId(updatedOrder.getTrackId())
                                        .orderId(updatedOrder.getOrderId())
                                        .items(reservedItems)
                                        .timestamp(Instant.now())
                                        .build();

                                return emitEvents(completedNotification, completedStatus,
                                        completeCmd);
                            });
                })
                .then()
                .doOnSuccess(
                        v -> log.info("handlePaymentSuccessOrders() -> Completed for trackId={}",
                                trackIdStr))
                .doOnError(e -> log.error(
                        "handlePaymentSuccessOrders() -> Error for trackId={}, error={}",
                        trackIdStr, e.getMessage()));
    }

    public Mono<Void> handleInventoryReserveFailedEvent(String eventPayload) {
        try {
            JsonNode rootNode = defaultObjectMapper.readTree(eventPayload);
            String orderIdStr = rootNode.has("orderId") ? rootNode.get("orderId").asText() : null;
            String trackIdStr = rootNode.has("trackId") ? rootNode.get("trackId").asText() : null;
            JsonNode reasonsNode = rootNode.get("reasons");
            List<String> reasonLines = new ArrayList<>();
            if (reasonsNode != null && reasonsNode.isArray()) {
                for (JsonNode reasonNode : reasonsNode) {
                    String productId =
                            reasonNode.has("productId") ? reasonNode.get("productId").asText()
                                    : "null";
                    String productName =
                            reasonNode.has("productName") ? reasonNode.get("productName").asText()
                                    : "null";
                    String message =
                            reasonNode.has("message") ? reasonNode.get("message").asText() : "null";
                    reasonLines.add(
                            String.format("The product %s with product id %s: %s", productName,
                                    productId, message));
                }
            }
            String combinedReason = String.join("\n", reasonLines);
            UUID orderId = UUID.fromString(orderIdStr);

            return orderRepository.findById(orderId)
                    .switchIfEmpty(Mono.error(
                            new OrderNotFoundException("Order not found, id=" + orderId)))
                    .flatMap(order -> {
                        order.setStatus(OrderStatus.ORDER_CANCELLED);
                        order.setCancellationReason(combinedReason);
                        order.setUpdatedAt(Instant.now());
                        return orderRepository.save(order);
                    })
                    .flatMap(updatedOrder -> {
                        OrderCancelledEvent cancelledStatus = new OrderCancelledEvent();
                        cancelledStatus.setEventType(EventType.ORDER_CANCELLED_STATUS.name());
                        cancelledStatus.setOrderId(updatedOrder.getOrderId());
                        cancelledStatus.setReason(combinedReason);
                        cancelledStatus.setTimestamp(Instant.now());

                        OrderCancelledEvent cancelledNotification = new OrderCancelledEvent();
                        cancelledNotification.setEventType(
                                EventType.ORDER_CANCELLED_NOTIFICATION.name());
                        cancelledNotification.setOrderId(updatedOrder.getOrderId());
                        cancelledNotification.setReason(combinedReason);
                        cancelledNotification.setTimestamp(Instant.now());

                        return emitEvents(cancelledStatus, cancelledNotification);
                    });
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private Mono<Order> emitInitiatePaymentCommand(Order order) {
        if (order.getItems() == null) {
            log.warn("emitInitiatePaymentCommand: order.getItems() is null for orderId={}",
                    order.getOrderId());
        }
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
                .items(order.getItems() != null
                        ? order.getItems().stream()
                        .map(oi -> InitiatePaymentCommand.OrderItemEvent.builder()
                                .productId(oi.getProductId())
                                .quantity(oi.getQuantity())
                                .build())
                        .toList()
                        : new ArrayList<>())
                .timestamp(Instant.now())
                .build();
        return emitEvent(paymentCommand).thenReturn(order);
    }

    private Mono<Void> emitEvents(Event... events) {
        return Flux.fromArray(events).flatMap(this::emitEvent).then();
    }

    private Mono<Void> emitEvent(Event event) {
        String topic = getTopicForEvent(event.getEventType());
        SenderRecord<String, Object, String> record = SenderRecord.create(
                new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, event),
                event.getEventType()
        );
        return kafkaSender.send(Mono.just(record)).then();
    }

    private String getTopicForEvent(String eventType) {
        for (EventType type : EventType.values()) {
            if (type.name().equals(eventType)) {
                return type.getTopic();
            }
        }
        return KafkaTopics.ORDER_STATUS_EVENTS_TOPIC;
    }
}
