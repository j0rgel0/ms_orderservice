package com.lox.orderservice.api.kafka.listeners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lox.orderservice.api.services.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusEventListener {

    private final OrderService orderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "order.status.events", groupId = "order-service-group")
    public void listenOrderStatusEvents(String message) {
        log.info("order.status.events - Received inventory event: {}", message);
        Mono<Void> result = orderService.handleOrderStatusEvent(message);
        result.subscribe();
    }
    @KafkaListener(topics = "inventory.events", groupId = "order-service-group")
    public void listenInventoryEvents(String message) {
        log.info("inventory.events - Received inventory event: {}", message);
        Mono<Void> result = orderService.handleOrderStatusEvent(message);
        result.subscribe();
    }
}
