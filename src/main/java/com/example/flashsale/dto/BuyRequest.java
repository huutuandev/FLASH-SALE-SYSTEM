package com.example.flashsale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyRequest {
    private Long flashSaleId;
    private Long productId;
    private Integer quantity;
    private String idempotencyKey;
    private Long timestamp;
    private String signature;
}
