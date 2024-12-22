// OrderServiceImpl.java
package com.lox.orderservice.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.lox.orderservice.api.models.dto.OrderRequest;
import com.lox.orderservice.api.models.dto.OrderResponse;
import com.lox.orderservice.api.repositories.r2dbc.OrderItemRepository;
import com.lox.orderservice.api.repositories.r2dbc.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
        log.debug("Creating order for userId: {}", orderRequest.getUserId());

        BigDecimal totalAmount = BigDecimal.ZERO;

        // Generate trackId in the service layer
        UUID trackId = UUID.randomUUID();

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

        // Create the order with trackId
        Order order = Order.builder()
                .userId(orderRequest.getUserId())
                .trackId(trackId) // Assign trackId here
                .totalAmount(totalAmount)
                .currency(orderRequest.getCurrency())
                .status(OrderStatus.ORDER_CREATED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Save the order to the database
        return orderRepository.save(order)
                .flatMap(savedOrder -> {
                    log.debug("Order saved with orderId: {}", savedOrder.getOrderId());

                    // Assign orderId to each OrderItem
                    orderItems.forEach(item -> item.setOrderId(savedOrder.getOrderId()));

                    // Save OrderItems
                    return orderItemRepository.saveAll(orderItems)
                            .collectList()
                            .flatMap(savedOrderItems -> {
                                log.debug("OrderItems saved: {}", savedOrderItems);

                                // Set the items list in the savedOrder
                                savedOrder.setItems(savedOrderItems);

                                // Emit the OrderCreatedEvent
                                return emitEvent(OrderCreatedEvent.fromOrder(savedOrder))
                                        .then(Mono.defer(() -> {
                                            OrderResponse response = orderMapper.toOrderResponse(savedOrder);
                                            log.debug("OrderResponse generated: {}", response);
                                            return Mono.just(response);
                                        }));
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error creating order for userId: {}", orderRequest.getUserId(), e);
                    return Mono.error(new RuntimeException("Error creating order", e));
                });
    }

    @Override
    public Mono<OrderResponse> getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> orderItemRepository.findByOrderId(orderId)
                        .collectList()
                        .map(items -> {
                            order.setItems(items);
                            return order;
                        }))
                .map(orderMapper::toOrderResponse);
    }

    @Override
    @Transactional
    public Mono<OrderResponse> updateOrderStatus(UUID orderId, String newStatus) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(existingOrder -> {
                    OrderStatus updatedStatus;
                    try {
                        updatedStatus = OrderStatus.valueOf(newStatus.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return Mono.error(new InvalidOrderStatusException(
                                "Invalid order status: " + newStatus));
                    }
                    existingOrder.setStatus(updatedStatus);
                    existingOrder.setUpdatedAt(Instant.now());
                    return orderRepository.save(existingOrder)
                            .flatMap(updatedOrder -> {
                                OrderUpdatedEvent orderUpdatedEvent = OrderUpdatedEvent.fromOrder(
                                        updatedOrder);
                                if (updatedStatus == OrderStatus.ORDER_CANCELLED) {
                                    // Here you should obtain the cancellation reason from some source,
                                    // for example, from the event or the request.
                                    // Let's assume it's passed as part of the newStatus JSON.
                                    String reason = "Cancellation reason"; // Replace with actual logic
                                    OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.builder()
                                            .eventType(EventType.ORDER_CANCELLED.name())
                                            .orderId(orderId)
                                            .reason(reason)
                                            .timestamp(Instant.now())
                                            .build();
                                    return emitEvents(orderUpdatedEvent, orderCancelledEvent)
                                            .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                                }
                                return emitEvent(orderUpdatedEvent)
                                        .thenReturn(orderMapper.toOrderResponse(updatedOrder));
                            });
                });
    }

    @Override
    @Transactional
    public Mono<Void> deleteOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> orderRepository.delete(order)
                        .then(emitEvent(OrderRemovedEvent.fromOrder(order))))
                .then();
    }

    @Override
    public Mono<OrderPage> listOrders(String status, UUID userId, Instant startDate,
            Instant endDate, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return orderRepository.findByStatusAndUserIdAndCreatedAtBetween(status, userId, startDate,
                        endDate, pageRequest)
                .flatMap(order -> orderItemRepository.findByOrderId(order.getOrderId())
                        .collectList()
                        .map(items -> {
                            order.setItems(items);
                            return order;
                        }))
                .map(orderMapper::toOrderResponse)
                .collectList()
                .zipWith(orderRepository.countByStatusAndUserIdAndCreatedAtBetween(status, userId,
                        startDate, endDate))
                .map(tuple -> OrderPage.builder()
                        .orders(tuple.getT1())
                        .totalElements(tuple.getT2())
                        .totalPages((int) Math.ceil((double) tuple.getT2() / size))
                        .currentPage(page)
                        .build());
    }

    @Override
    @Transactional
    public Mono<Void> handleOrderStatusEvent(String eventPayload) {
        try {
            JsonNode jsonNode = objectMapper.readTree(eventPayload);
            String eventType = jsonNode.get("eventType").asText();
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
                    return Mono.empty();
            }
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Invalid event payload", e));
        }
    }

    private Mono<Void> handleInventoryReservedEvent(JsonNode jsonNode) {
        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> {
                    InitiatePaymentCommand initiatePaymentCommand = InitiatePaymentCommand.builder()
                            .eventType(EventType.INITIATE_PAYMENT_COMMAND.name())
                            .orderId(orderId)
                            .totalAmount(order.getTotalAmount())
                            .timestamp(Instant.now())
                            .build();
                    return emitEvent(initiatePaymentCommand);
                })
                .then();
    }

    private Mono<Void> handleInventoryReserveFailedEvent(JsonNode jsonNode) {
        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "Unknown";
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> {
                    order.setStatus(OrderStatus.ORDER_CANCELLED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(savedOrder -> {
                                OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.builder()
                                        .eventType(EventType.ORDER_CANCELLED.name())
                                        .orderId(orderId)
                                        .reason(reason)
                                        .timestamp(Instant.now())
                                        .build();
                                return emitEvent(orderCancelledEvent);
                            });
                })
                .then();
    }

    private Mono<Void> handlePaymentSucceededEvent(JsonNode jsonNode) {
        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> {
                    order.setStatus(OrderStatus.ORDER_COMPLETED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(savedOrder -> {
                                OrderCompletedEvent orderCompletedEvent = OrderCompletedEvent.builder()
                                        .eventType(EventType.ORDER_COMPLETED.name())
                                        .orderId(orderId)
                                        .timestamp(Instant.now())
                                        .build();
                                return emitEvent(orderCompletedEvent);
                            });
                })
                .then();
    }

    private Mono<Void> handlePaymentFailedEvent(JsonNode jsonNode) {
        UUID orderId = UUID.fromString(jsonNode.get("orderId").asText());
        String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "Unknown";
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(
                        new OrderNotFoundException("Order not found with ID: " + orderId)))
                .flatMap(order -> {
                    order.setStatus(OrderStatus.ORDER_CANCELLED);
                    order.setUpdatedAt(Instant.now());
                    return orderRepository.save(order)
                            .flatMap(savedOrder -> {
                                OrderCancelledEvent orderCancelledEvent = OrderCancelledEvent.builder()
                                        .eventType(EventType.ORDER_CANCELLED.name())
                                        .orderId(orderId)
                                        .reason(reason)
                                        .timestamp(Instant.now())
                                        .build();
                                ReleaseInventoryCommand releaseInventoryCommand = ReleaseInventoryCommand.builder()
                                        .eventType(EventType.RELEASE_INVENTORY_COMMAND.name())
                                        .orderId(orderId)
                                        .productId(savedOrder.getItems().getFirst().getProductId())
                                        .quantity(savedOrder.getItems().getFirst().getQuantity())
                                        .timestamp(Instant.now())
                                        .build();
                                return emitEvents(orderCancelledEvent, releaseInventoryCommand);
                            });
                })
                .then();
    }

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
        return kafkaSender.send(Mono.just(record))
                .then();
    }

    private String getTopicForEvent(String eventType) {
        for (EventType type : EventType.values()) {
            if (type.name().equals(eventType)) {
                return type.getTopic();
            }
        }
        return KafkaTopics.ORDER_EVENTS;
    }
}
