package com.lox.orderservice.api.kafka.events;

import com.lox.orderservice.api.kafka.topics.KafkaTopics;
import lombok.Getter;

@Getter
public enum EventType {
    ORDER_CREATED(KafkaTopics.ORDER_EVENTS),
    ORDER_UPDATED(KafkaTopics.ORDER_EVENTS),
    ORDER_CANCELLED(KafkaTopics.ORDER_EVENTS),
    ORDER_REMOVED(KafkaTopics.ORDER_EVENTS),
    INVENTORY_RESERVED(KafkaTopics.INVENTORY_EVENTS),
    INVENTORY_RELEASED(KafkaTopics.INVENTORY_EVENTS),
    PAYMENT_INITIATED(KafkaTopics.PAYMENT_EVENTS),
    PAYMENT_CANCELLED(KafkaTopics.PAYMENT_EVENTS),
    ORDER_COMPLETED(KafkaTopics.ORDER_EVENTS),
    INVENTORY_RELEASED_CONFIRMED(KafkaTopics.INVENTORY_EVENTS),
    PAYMENT_CANCELLED_CONFIRMED(KafkaTopics.PAYMENT_EVENTS);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }
}
