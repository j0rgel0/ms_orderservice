// OrderMapper.java
package com.lox.orderservice.api.mappers;

import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.dto.OrderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface OrderMapper {

    OrderMapper INSTANCE = Mappers.getMapper(OrderMapper.class);

    @Mapping(source = "trackId", target = "trackId")
    @Mapping(source = "cancellationReason", target = "cancellationReason")
    OrderResponse toOrderResponse(Order order);


}
