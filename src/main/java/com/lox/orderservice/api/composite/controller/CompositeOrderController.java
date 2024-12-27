package com.lox.orderservice.api.composite.controller;

import com.lox.orderservice.api.composite.CompositeOrderService;
import com.lox.orderservice.api.composite.dto.CompositeOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/composite")
@RequiredArgsConstructor
@Slf4j
public class CompositeOrderController {

    private final CompositeOrderService compositeOrderService;

    /**
     * A single GET endpoint to retrieve all data related to a given trackId.
     */
    @GetMapping("/{trackId}")
    public Mono<CompositeOrderResponse> getOrderCompositeByTrackId(
            @PathVariable("trackId") UUID trackId) {

        log.info("Received request to fetch composite data for trackId: {}", trackId);

        return compositeOrderService.getCompositeByTrackId(trackId)
                .doOnSuccess(response -> {
                    if (response != null) {
                        log.info("Successfully fetched composite data for trackId: {}", trackId);
                    } else {
                        log.info("No data found for trackId: {}", trackId);
                    }
                })
                .doOnError(e ->
                        log.error("Error fetching composite data for trackId: {}. Cause: {}", trackId, e.getMessage(), e)
                );
    }
}
