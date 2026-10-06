package com.example.flashsale.service;

import com.example.flashsale.dto.BuyRequest;
import com.example.flashsale.dto.BuyResponse;
import com.example.flashsale.event.InventoryReservedEvent;
import com.example.flashsale.security.AntiBotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleService {

    private final AntiBotService antiBotService;
    private final RedisInventoryService redisInventoryService;
    private final RabbitMQPublisher rabbitMQPublisher;

    public BuyResponse processBuyRequest(Long userId, String ipAddress, BuyRequest request) {
        // 1. Anti-bot and Security Validation
        antiBotService.validateRequest(userId, ipAddress, request);

        // 2. Redis Inventory Reservation
        ReservationResult result = redisInventoryService.reserveInventory(
                request.getFlashSaleId(),
                userId,
                request.getQuantity(),
                request.getIdempotencyKey()
        );

        String requestId = UUID.randomUUID().toString();

        if (result == ReservationResult.SUCCESS) {
            // 3. Publish to RabbitMQ
            InventoryReservedEvent event = InventoryReservedEvent.builder()
                    .eventId(requestId)
                    .reservationId(request.getIdempotencyKey()) // using idempotencyKey as reservation reference
                    .userId(userId)
                    .flashSaleId(request.getFlashSaleId())
                    .productId(request.getProductId())
                    .quantity(request.getQuantity())
                    .idempotencyKey(request.getIdempotencyKey())
                    .createdAt(OffsetDateTime.now())
                    .build();

            rabbitMQPublisher.publishReservationEvent(event);

            return BuyResponse.builder()
                    .requestId(requestId)
                    .reservationId(request.getIdempotencyKey())
                    .status("RESERVED")
                    .build();
            
        } else if (result == ReservationResult.SOLD_OUT) {
            return BuyResponse.builder()
                    .requestId(requestId)
                    .status("SOLD_OUT")
                    .build();
        } else if (result == ReservationResult.DUPLICATE_REQUEST) {
            // For duplicate request, we can just return ACCEPTED to not break idempotency
            return BuyResponse.builder()
                    .requestId(requestId)
                    .reservationId(request.getIdempotencyKey())
                    .status("RESERVED")
                    .build();
        } else {
            throw new IllegalArgumentException("Invalid reservation state: " + result);
        }
    }
}
