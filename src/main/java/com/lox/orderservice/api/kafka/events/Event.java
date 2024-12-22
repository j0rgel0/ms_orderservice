package com.lox.orderservice.api.kafka.events;

import java.time.Instant;
import java.util.UUID;

public interface Event {

    String getEventType();

    UUID getOrderId();

    Instant getTimestamp();
}
