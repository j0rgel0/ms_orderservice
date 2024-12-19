package com.lox.orderservice.api.kafka.events;

import java.time.Instant;
import java.util.UUID;

public interface Event {

    /**
     * Returns the type of the event.
     *
     * @return Event type as a String.
     */
    String getEventType();

    /**
     * Returns the associated order ID.
     *
     * @return Order ID as UUID.
     */
    UUID getOrderId();

    /**
     * Returns the timestamp of the event.
     *
     * @return Timestamp as Instant.
     */
    Instant getTimestamp();
}
