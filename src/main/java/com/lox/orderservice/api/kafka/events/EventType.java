package com.lox.orderservice.api.kafka.events;

import com.lox.orderservice.api.kafka.topics.KafkaTopics;
import lombok.Getter;

@Getter
public enum EventType {
    ORDER_CREATED_NOTIFICATION(KafkaTopics.NOTIFICATION_EVENTS_TOPIC), //Notification
    ORDER_UPDATED_NOTIFICATION(KafkaTopics.NOTIFICATION_EVENTS_TOPIC), //Notification
    ORDER_CANCELLED_NOTIFICATION(KafkaTopics.NOTIFICATION_EVENTS_TOPIC), //Notification
    ORDER_REMOVED_NOTIFICATION(KafkaTopics.NOTIFICATION_EVENTS_TOPIC), //Notification
    ORDER_COMPLETED_NOTIFICATION(KafkaTopics.NOTIFICATION_EVENTS_TOPIC), //Notification

    ORDER_CREATED_STATUS(KafkaTopics.ORDER_STATUS_EVENTS_TOPIC), //Order Status
    ORDER_UPDATED_STATUS(KafkaTopics.ORDER_STATUS_EVENTS_TOPIC), //Order Status
    ORDER_CANCELLED_STATUS(KafkaTopics.ORDER_STATUS_EVENTS_TOPIC), //Order Status
    ORDER_REMOVED_STATUS(KafkaTopics.ORDER_STATUS_EVENTS_TOPIC), //Order Status
    ORDER_COMPLETED_STATUS(KafkaTopics.ORDER_STATUS_EVENTS_TOPIC), //Order Status

    //PEnding
    RELEASE_INVENTORY_COMMAND(KafkaTopics.INVENTORY_COMMANDS_TOPIC),

    // (1)
    RESERVE_INVENTORY_COMMAND(KafkaTopics.INVENTORY_COMMANDS_TOPIC),

    // (2)
    INITIATE_PAYMENT_COMMAND(KafkaTopics.PAYMENT_COMMANDS_TOPIC),

    // (3)
    COMPLETE_INVENTORY_COMMAND(KafkaTopics.INVENTORY_COMMANDS_TOPIC);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }
}
