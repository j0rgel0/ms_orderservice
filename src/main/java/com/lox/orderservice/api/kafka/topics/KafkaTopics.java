package com.lox.orderservice.api.kafka.topics;

import com.lox.orderservice.common.kafka.KafkaConfig;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class KafkaTopics {

    public static final String ORDER_EVENTS = "order-events";
    public static final String INVENTORY_EVENTS = "inventory-events";
    public static final String PAYMENT_EVENTS = "payment-events";

    private final KafkaConfig kafkaConfig;

    /**
     * Creates the ORDER_EVENTS Kafka topic.
     *
     * @return A NewTopic instance for ORDER_EVENTS.
     */
    @Bean
    public NewTopic orderEventsTopic() {
        return kafkaConfig.createTopic(ORDER_EVENTS, 0, (short) 0);
    }

    /**
     * Creates the INVENTORY_EVENTS Kafka topic.
     *
     * @return A NewTopic instance for INVENTORY_EVENTS.
     */
    @Bean
    public NewTopic inventoryEventsTopic() {
        return kafkaConfig.createTopic(INVENTORY_EVENTS, 0, (short) 0);
    }

    /**
     * Creates the PAYMENT_EVENTS Kafka topic.
     *
     * @return A NewTopic instance for PAYMENT_EVENTS.
     */
    @Bean
    public NewTopic paymentEventsTopic() {
        return kafkaConfig.createTopic(PAYMENT_EVENTS, 0, (short) 0);
    }
}
