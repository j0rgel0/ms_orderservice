package com.lox.orderservice.api.kafka.topics;

import com.lox.orderservice.common.kafka.KafkaConfig;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class KafkaTopics {

    public static final String NOTIFICATION_EVENTS_TOPIC = "notification.events";
    public static final String ORDER_STATUS_EVENTS_TOPIC = "order.status.events";
    public static final String INVENTORY_COMMANDS_TOPIC = "inventory.commands";
    public static final String PAYMENT_COMMANDS_TOPIC = "payment.commands";

    private final KafkaConfig kafkaConfig;

    @Bean
    public NewTopic notificationsEventsTopic() {
        return kafkaConfig.createTopic(NOTIFICATION_EVENTS_TOPIC, 3, (short) 1);
    }

    @Bean
    public NewTopic orderStatusEventsTopic() {
        return kafkaConfig.createTopic(ORDER_STATUS_EVENTS_TOPIC, 3, (short) 1);
    }

    @Bean
    public NewTopic inventoryCommandsTopic() {
        return kafkaConfig.createTopic(INVENTORY_COMMANDS_TOPIC, 3, (short) 1);
    }

    @Bean
    public NewTopic paymentCommandsTopic() {
        return kafkaConfig.createTopic(PAYMENT_COMMANDS_TOPIC, 3, (short) 1);
    }

}
