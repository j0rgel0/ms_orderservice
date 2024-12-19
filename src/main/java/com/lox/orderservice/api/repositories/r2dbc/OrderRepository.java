package com.lox.orderservice.api.repositories.r2dbc;

import com.lox.orderservice.api.models.Order;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends ReactiveCrudRepository<Order, UUID>,
        OrderRepositoryCustom {

}
