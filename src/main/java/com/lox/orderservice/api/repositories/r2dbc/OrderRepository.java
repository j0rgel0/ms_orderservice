package com.lox.orderservice.api.repositories.r2dbc;

import com.lox.orderservice.api.models.Order;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OrderRepository extends ReactiveCrudRepository<Order, UUID>,
        OrderRepositoryCustom {

    Flux<Order> findByStatusAndUserIdAndCreatedAtBetween(String status, UUID userId,
            Instant startDate, Instant endDate, Pageable pageable);

    Mono<Long> countByStatusAndUserIdAndCreatedAtBetween(String status, UUID userId,
            Instant startDate, Instant endDate);
    Mono<Order> findByTrackId(UUID trackId);

}
