package com.lox.orderservice.api.mappers;

import com.lox.orderservice.api.dto.OrderItemResponse;
import com.lox.orderservice.api.dto.OrderResponse;
import com.lox.orderservice.api.models.Order;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "items", source = "items")
    OrderResponse toOrderResponse(Order order);

    List<OrderItemResponse> toOrderItemResponses(
            List<com.lox.orderservice.api.models.OrderItem> items);
}
