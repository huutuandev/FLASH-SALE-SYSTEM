package com.example.flashsale.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class  InventoryReservedEvent {
    private String eventId;
    private String reservationId; // Can be idempotencyKey or separate
    private Long userId;
    private Long flashSaleId;
    private Long productId;
    private Integer quantity;
    private String idempotencyKey;
    private OffsetDateTime createdAt;
}
