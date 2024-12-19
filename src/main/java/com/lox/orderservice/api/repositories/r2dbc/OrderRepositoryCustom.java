package com.lox.orderservice.api.repositories.r2dbc;

import com.lox.orderservice.api.models.Order;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderRepositoryCustom {

    /**
     * Finds orders based on optional filters with pagination.
     *
     * @param status      Optional. Order status.
     * @param userId      Optional. User ID.
     * @param startDate   Optional. Start date for filtering.
     * @param endDate     Optional. End date for filtering.
     * @param pageRequest Pagination information.
     * @return A Flux of orders that match the filters.
     */
    Flux<Order> findOrdersByFilters(String status, UUID userId, Instant startDate, Instant endDate,
            PageRequest pageRequest);

    /**
     * Counts the number of orders that match the optional filters.
     *
     * @param status    Optional. Order status.
     * @param userId    Optional. User ID.
     * @param startDate Optional. Start date for filtering.
     * @param endDate   Optional. End date for filtering.
     * @return A Mono with the count of matching orders.
     */
    Mono<Long> countOrdersByFilters(String status, UUID userId, Instant startDate, Instant endDate);
}
