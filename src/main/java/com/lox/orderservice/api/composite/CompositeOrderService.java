package com.lox.orderservice.api.composite;

import com.lox.orderservice.api.composite.client.ExternalServicesClient;
import com.lox.orderservice.api.composite.dto.CompositeOrderResponse;
import com.lox.orderservice.api.composite.dto.CompositeOrderResponse.InventoryData;
import com.lox.orderservice.api.composite.dto.CompositeOrderResponse.OrderItemInfo;
import com.lox.orderservice.api.composite.dto.CompositeOrderResponse.PaymentInfo;
import com.lox.orderservice.api.composite.dto.InventoryResponse;
import com.lox.orderservice.api.models.Order;
import com.lox.orderservice.api.models.OrderItem;
import com.lox.orderservice.api.repositories.r2dbc.OrderItemRepository;
import com.lox.orderservice.api.repositories.r2dbc.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompositeOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ExternalServicesClient externalServicesClient;

    public Mono<CompositeOrderResponse> getCompositeByTrackId(UUID trackId) {
        log.info("Looking up Order by trackId={}", trackId);

        // 1) Find the Order
        return orderRepository.findByTrackId(trackId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No Order found for trackId={}", trackId);
                    return Mono.empty();
                }))
                .flatMap(order -> {
                    // 2) Build items with product info
                    Mono<List<OrderItemInfo>> itemsMono = orderItemRepository.findByOrderId(
                                    order.getOrderId())
                            .flatMap(this::buildOrderItemInfo)
                            .collectList();

                    // 3) Fetch payments
                    Flux<PaymentInfo> paymentsFlux = externalServicesClient.getPaymentsByTrackId(
                            trackId);

                    // 4) Combine items + payments, then fetch inventory for distinct productIds
                    return Mono.zip(itemsMono, paymentsFlux.collectList())
                            .flatMap(tuple -> {
                                List<OrderItemInfo> items = tuple.getT1();
                                List<PaymentInfo> payments = tuple.getT2();

                                // Distinct productIds from items
                                List<UUID> productIds = items.stream()
                                        .map(OrderItemInfo::getProductId)
                                        .distinct()
                                        .toList();

                                return Flux.fromIterable(productIds)
                                        .flatMap(
                                                productId -> externalServicesClient.getInventoryResponseByProductId(
                                                                productId)
                                                        .onErrorResume(e -> {
                                                            // If Inventory Service fails, skip
                                                            log.error(
                                                                    "Inventory error for productId={}. reason={}",
                                                                    productId, e.getMessage());
                                                            return Mono.empty();
                                                        })
                                        )
                                        .map(this::convertToInventoryData)
                                        .collectList()
                                        .map(inventoryDataList -> buildCompositeResponse(order,
                                                items, payments, inventoryDataList));
                            });
                })
                .doOnError(
                        e -> log.error("Error in getCompositeByTrackId for trackId={}. reason={}",
                                trackId, e.getMessage(), e));
    }

    /**
     * Convert InventoryService's response to our InventoryData object in the final composite
     */
    private InventoryData convertToInventoryData(InventoryResponse invResp) {
        // inventory
        InventoryResponse.InventoryDto inv = invResp.getInventory();
        // product
        InventoryResponse.ProductDto prod = invResp.getProduct();

        return InventoryData.builder()
                .inventoryId(inv.getInventoryId())
                .productId(inv.getProductId())
                .availableQuantity(inv.getAvailableQuantity())
                .reservedQuantity(inv.getReservedQuantity())
                .reorderLevel(inv.getReorderLevel())
                .reorderQuantity(inv.getReorderQuantity())
                .lastUpdated(inv.getLastUpdated())
                .product(
                        CompositeOrderResponse.ProductInfo.builder()
                                .productId(prod.getProductId())
                                .name(prod.getName())
                                .description(prod.getDescription())
                                .price(
                                        prod.getPrice() != null
                                                ? BigDecimal.valueOf(prod.getPrice())
                                                // adapt if you use BigDecimal
                                                : BigDecimal.ZERO
                                )
                                .category(prod.getCategory())
                                .availableQuantity(prod.getAvailableQuantity())
                                .createdAt(prod.getCreatedAt())
                                .updatedAt(prod.getUpdatedAt())
                                .build()
                )
                .build();
    }

    /**
     * Build a single OrderItem with product info only, no inventory.
     */
    private Mono<OrderItemInfo> buildOrderItemInfo(OrderItem orderItem) {
        log.debug("Enriching OrderItem={} with product info only", orderItem.getOrderItemId());

        return externalServicesClient.getProductById(orderItem.getProductId())
                .map(product ->
                        OrderItemInfo.builder()
                                .orderItemId(orderItem.getOrderItemId())
                                .productId(orderItem.getProductId())
                                .quantity(orderItem.getQuantity())
                                .price(orderItem.getPrice())
                                .createdAt(orderItem.getCreatedAt())
                                .updatedAt(orderItem.getUpdatedAt())
                                .product(product)
                                .build()
                )
                .onErrorResume(e -> {
                    // If product fails, fallback
                    log.error("Error fetching product for productId={}. reason={}",
                            orderItem.getProductId(), e.getMessage());
                    return Mono.just(
                            OrderItemInfo.builder()
                                    .orderItemId(orderItem.getOrderItemId())
                                    .productId(orderItem.getProductId())
                                    .quantity(orderItem.getQuantity())
                                    .price(orderItem.getPrice())
                                    .createdAt(orderItem.getCreatedAt())
                                    .updatedAt(orderItem.getUpdatedAt())
                                    .build()
                    );
                });
    }

    private CompositeOrderResponse buildCompositeResponse(
            Order order,
            List<OrderItemInfo> items,
            List<PaymentInfo> payments,
            List<InventoryData> inventoryDataList
    ) {
        log.debug("Building CompositeOrderResponse for orderId={}", order.getOrderId());
        return CompositeOrderResponse.builder()
                .orderId(order.getOrderId())
                .trackId(order.getTrackId())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .status(String.valueOf(order.getStatus()))
                .cancellationReason(order.getCancellationReason())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .orderItems(items)
                .payments(payments)
                .currentInventories(inventoryDataList)
                .build();
    }
}
