package com.lox.orderservice.api.models;

import com.lox.orderservice.api.dto.OrderResponse;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a paginated response for orders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPage {

    /**
     * List of orders for the current page.
     */
    private List<OrderResponse> orders;

    /**
     * Total number of orders matching the filter criteria.
     */
    private long totalElements;

    /**
     * Total number of pages available.
     */
    private int totalPages;

    /**
     * Current page number (0-based index).
     */
    private int currentPage;
}
