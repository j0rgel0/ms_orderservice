package com.lox.orderservice.api.kafka.topics;

import com.lox.orderservice.common.kafka.KafkaConfig;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class KafkaTopics {

    public static final String ORDER_EVENTS = "order.events";
    public static final String INVENTORY_RELEASE_EVENTS = "inventory.release.events";
    public static final String ORDER_STATUS_EVENTS = "order.status.events";
    public static final String INVENTORY_COMMANDS = "inventory.commands";
    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String PAYMENT_EVENTS = "payment.events";

    private final KafkaConfig kafkaConfig;

    @Bean
    public NewTopic orderEventsTopic() {
        return kafkaConfig.createTopic(ORDER_EVENTS, 3, (short) 1);
    }

    @Bean
    public NewTopic inventoryReleaseEventsTopic() {
        return kafkaConfig.createTopic(INVENTORY_RELEASE_EVENTS, 3, (short) 1);
    }

    @Bean
    public NewTopic orderStatusEventsTopic() {
        return kafkaConfig.createTopic(ORDER_STATUS_EVENTS, 3, (short) 1);
    }

    @Bean
    public NewTopic inventoryCommandsTopic() {
        return kafkaConfig.createTopic(INVENTORY_COMMANDS, 3, (short) 1);
    }

    @Bean
    public NewTopic paymentCommandsTopic() {
        return kafkaConfig.createTopic(PAYMENT_COMMANDS, 3, (short) 1);
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return kafkaConfig.createTopic(PAYMENT_EVENTS, 3, (short) 1);
    }
}
