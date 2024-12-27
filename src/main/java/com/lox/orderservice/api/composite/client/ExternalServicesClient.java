package com.lox.orderservice.api.composite.client;

import com.lox.orderservice.api.composite.dto.CompositeOrderResponse.PaymentInfo;
import com.lox.orderservice.api.composite.dto.CompositeOrderResponse.ProductInfo;
import com.lox.orderservice.api.composite.dto.InventoryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@Slf4j
public class ExternalServicesClient {

    private final WebClient paymentWebClient;
    private final WebClient productWebClient;
    private final WebClient inventoryWebClient;

    public ExternalServicesClient(
            @Value("${services.payment-service.url}") String paymentServiceUrl,
            @Value("${services.product-service.url}") String productServiceUrl,
            @Value("${services.inventory-service.url}") String inventoryServiceUrl
    ) {
        this.paymentWebClient = WebClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();

        this.productWebClient = WebClient.builder()
                .baseUrl(productServiceUrl)
                .build();

        this.inventoryWebClient = WebClient.builder()
                .baseUrl(inventoryServiceUrl)
                .build();
    }

    /**
     * Payment Service: e.g. GET /api/payments/searchByTrackId?trackId={trackId}
     */
    public Flux<PaymentInfo> getPaymentsByTrackId(UUID trackId) {
        log.info("Calling Payment Service for trackId={}", trackId);

        return paymentWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/payments/searchByTrackId")
                        .queryParam("trackId", trackId)
                        .build())
                .retrieve()
                .bodyToFlux(PaymentInfo.class)
                .doOnNext(p -> log.debug("Payment data: {}", p))
                .doOnError(e -> log.error("Error calling Payment Service. trackId={}, reason={}",
                        trackId, e.getMessage(), e));
    }

    /**
     * Product Catalog Service: e.g. GET /api/products/{id}
     */
    public Mono<ProductInfo> getProductById(UUID productId) {
        log.info("Calling Product Catalog Service for productId={}", productId);

        return productWebClient.get()
                .uri("/api/products/{id}", productId)
                .retrieve()
                .bodyToMono(ProductInfo.class)
                .doOnNext(prod -> log.debug("Product data: {}", prod))
                .doOnError(e -> log.error("Error calling Product Service. productId={}, reason={}",
                        productId, e.getMessage(), e));
    }

    /**
     * Inventory Service: GET /api/inventory/{productId}
     * returns InventoryResponse (inventory + product).
     */
    public Mono<InventoryResponse> getInventoryResponseByProductId(UUID productId) {
        log.info("Calling Inventory Service for productId={}", productId);

        return inventoryWebClient.get()
                .uri("/api/inventory/{productId}", productId)
                .retrieve()
                .bodyToMono(InventoryResponse.class)
                .doOnNext(inv -> log.debug("Inventory data: {}", inv))
                .doOnError(e -> log.error("Error calling Inventory Service. productId={}, reason={}",
                        productId, e.getMessage(), e));
    }
}
