package com.lox.orderservice.api.models.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReasonDetail {

    @JsonProperty("productId")
    private UUID productId;

    @JsonProperty("productName")
    private String productName;

    @JsonProperty("message")
    private String message;
}

