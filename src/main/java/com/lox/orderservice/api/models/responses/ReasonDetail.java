package com.lox.orderservice.api.models.responses;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(builder = ReasonDetail.ReasonDetailBuilder.class) // Add this annotation
public class ReasonDetail {
    private UUID productId;
    private String productName;
    private String message;

    // Ensure the builder class is public and properly annotated if needed
}
