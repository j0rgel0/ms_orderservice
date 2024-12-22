package com.lox.orderservice.api.kafka.events;

import com.lox.orderservice.api.kafka.topics.KafkaTopics;
import lombok.Getter;

@Getter
public enum EventType {
    ORDER_CREATED(KafkaTopics.ORDER_EVENTS),
    ORDER_UPDATED(KafkaTopics.ORDER_EVENTS),
    ORDER_CANCELLED(KafkaTopics.ORDER_EVENTS),
    ORDER_REMOVED(KafkaTopics.ORDER_EVENTS),
    ORDER_COMPLETED(KafkaTopics.ORDER_EVENTS),
    INVENTORY_RESERVED(KafkaTopics.INVENTORY_RELEASE_EVENTS),
    INVENTORY_RESERVE_FAILED(KafkaTopics.INVENTORY_RELEASE_EVENTS),
    PAYMENT_SUCCEEDED(KafkaTopics.PAYMENT_EVENTS),
    PAYMENT_FAILED(KafkaTopics.PAYMENT_EVENTS),
    INITIATE_PAYMENT_COMMAND(KafkaTopics.PAYMENT_COMMANDS),
    RESERVE_INVENTORY_COMMAND(KafkaTopics.INVENTORY_COMMANDS),
    RELEASE_INVENTORY_COMMAND(KafkaTopics.INVENTORY_COMMANDS);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }
}
