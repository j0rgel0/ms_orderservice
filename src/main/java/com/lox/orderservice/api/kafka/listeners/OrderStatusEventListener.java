package com.lox.orderservice.api.kafka.listeners;

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

    @KafkaListener(topics = "inventory.status.events", groupId = "order-service-group")
    public void listenInventoryEvents(String message) {
        log.info("listenInventoryEvents() -> Received from inventory.status.events: {}", message);
        Mono<Void> result = orderService.handleOrderStatusEvent(message);
        result.subscribe(
                v -> log.info("Successfully processed inventory status event: {}", message),
                e -> log.error("Error processing inventory status event: {}, error: {}", message,
                        e.getMessage())
        );
    }


    @KafkaListener(topics = "payment.status.events", groupId = "order-service-group")
    public void listenPaymentEvents(String message) {
        log.info("listenPaymentEvents() -> Received from payment.status.events: {}", message);
        Mono<Void> result = orderService.handlePaymentStatusEvent(message);
        result.subscribe(
                v -> log.info("listenPaymentEvents() -> Successfully processed payment event: {}",
                        message),
                e -> log.error(
                        "listenPaymentEvents() -> Error processing payment event: {}, error: {}",
                        message, e.getMessage())
        );
    }
}
