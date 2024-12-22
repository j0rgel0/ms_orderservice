package com.lox.orderservice.api.models;

import com.lox.orderservice.api.models.dto.OrderResponse;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPage {

    private List<OrderResponse> orders;
    private long totalElements;
    private int totalPages;
    private int currentPage;
}
