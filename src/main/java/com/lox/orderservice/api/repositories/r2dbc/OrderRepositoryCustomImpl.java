package com.lox.orderservice.api.repositories.r2dbc;

import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.enums.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryCustomImpl implements OrderRepositoryCustom {

    private final DatabaseClient databaseClient;

    @Override
    public Flux<Order> findOrdersByFilters(String status, UUID userId, Instant startDate,
            Instant endDate, PageRequest pageRequest) {
        // Build the SQL query with optional filters
        StringBuilder queryBuilder = new StringBuilder("SELECT * FROM orders WHERE 1=1");

        if (status != null && !status.isEmpty()) {
            queryBuilder.append(" AND status = :status");
        }
        if (userId != null) {
            queryBuilder.append(" AND user_id = :userId");
        }
        if (startDate != null) {
            queryBuilder.append(" AND created_at >= :startDate");
        }
        if (endDate != null) {
            queryBuilder.append(" AND created_at <= :endDate");
        }

        // Add ordering and pagination clauses
        queryBuilder.append(" ORDER BY created_at DESC");
        queryBuilder.append(" LIMIT :limit OFFSET :offset");

        // Execute the query
        return databaseClient.sql(queryBuilder.toString())
                .bind("status", status)
                .bind("userId", userId)
                .bind("startDate", startDate)
                .bind("endDate", endDate)
                .bind("limit", pageRequest.getPageSize())
                .bind("offset", pageRequest.getOffset())
                .map((row, metadata) -> {
                    // Map each row to an instance of Order
                    Order order = new Order();
                    order.setOrderId(row.get("order_id", UUID.class));
                    order.setUserId(row.get("user_id", UUID.class));
                    order.setTotalAmount(row.get("total_amount", java.math.BigDecimal.class));
                    order.setCurrency(row.get("currency", String.class));
                    order.setStatus(OrderStatus.valueOf(
                            row.get("status", String.class)));
                    order.setCreatedAt(row.get("created_at", Instant.class));
                    order.setUpdatedAt(row.get("updated_at", Instant.class));
                    return order;
                })
                .all();
    }

    @Override
    public Mono<Long> countOrdersByFilters(String status, UUID userId, Instant startDate,
            Instant endDate) {
        // Build the SQL query for counting with optional filters
        StringBuilder queryBuilder = new StringBuilder("SELECT COUNT(*) FROM orders WHERE 1=1");

        if (status != null && !status.isEmpty()) {
            queryBuilder.append(" AND status = :status");
        }
        if (userId != null) {
            queryBuilder.append(" AND user_id = :userId");
        }
        if (startDate != null) {
            queryBuilder.append(" AND created_at >= :startDate");
        }
        if (endDate != null) {
            queryBuilder.append(" AND created_at <= :endDate");
        }

        // Execute the query
        return databaseClient.sql(queryBuilder.toString())
                .bind("status", status)
                .bind("userId", userId)
                .bind("startDate", startDate)
                .bind("endDate", endDate)
                .map((row, metadata) -> row.get(0, Long.class))
                .one();
    }
}
