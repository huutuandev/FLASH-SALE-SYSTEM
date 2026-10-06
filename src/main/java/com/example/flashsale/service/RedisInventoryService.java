package com.example.flashsale.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisInventoryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisScript<Long> reserveInventoryScript;

    private static final String INVENTORY_KEY_PREFIX = "flash-sale:%d:inventory";
    private static final String USERS_KEY_PREFIX = "flash-sale:%d:users";
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:%s";
    
    // Default TTL for idempotency and tracking: 24 hours
    private static final String TTL_SECONDS = "86400";

    /**
     * Tries to reserve inventory atomically in Redis using a Lua script.
     * 
     * @param flashSaleId   The ID of the flash sale.
     * @param userId        The ID of the user requesting to buy.
     * @param quantity      The amount to buy (usually 1).
     * @param idempotencyKey A unique key to prevent duplicate processing.
     * @return ReservationResult indicating success or reason for failure.
     */
    public ReservationResult reserveInventory(Long flashSaleId, Long userId, int quantity, String idempotencyKey) {
        String inventoryKey = String.format(INVENTORY_KEY_PREFIX, flashSaleId);
        String usersKey = String.format(USERS_KEY_PREFIX, flashSaleId);
        String idempotentKey = String.format(IDEMPOTENCY_KEY_PREFIX, idempotencyKey);

        List<String> keys = List.of(inventoryKey, usersKey, idempotentKey);

        try {
            Long result = redisTemplate.execute(
                    reserveInventoryScript,
                    keys,
                    String.valueOf(quantity),
                    String.valueOf(userId),
                    TTL_SECONDS
            );

            if (result == null) {
                log.error("Redis Lua script returned null for reservationId: {}", idempotencyKey);
                return ReservationResult.ERROR;
            }

            return mapResultCodeToEnum(result.intValue(), idempotencyKey);
            
        } catch (Exception e) {
            log.error("Redis exception occurred during reservation. ReservationId: {}. Error: {}", idempotencyKey, e.getMessage());
            return ReservationResult.ERROR;
        }
    }

    private ReservationResult mapResultCodeToEnum(int code, String idempotencyKey) {
        switch (code) {
            case 1:
                log.info("Successfully reserved inventory. ReservationId: {}", idempotencyKey);
                return ReservationResult.SUCCESS;
            case 0:
                log.warn("Reservation failed - SOLD OUT. ReservationId: {}", idempotencyKey);
                return ReservationResult.SOLD_OUT;
            case -1:
                log.warn("Reservation failed - DUPLICATE REQUEST. ReservationId: {}", idempotencyKey);
                return ReservationResult.DUPLICATE_REQUEST;
            case -2:
                log.warn("Reservation failed - USER ALREADY RESERVED. ReservationId: {}", idempotencyKey);
                return ReservationResult.USER_ALREADY_RESERVED;
            case -3:
                log.warn("Reservation failed - INVALID OR NOT STARTED. ReservationId: {}", idempotencyKey);
                return ReservationResult.INVALID_OR_NOT_STARTED;
            default:
                log.error("Unknown result code {} for ReservationId: {}", code, idempotencyKey);
                return ReservationResult.ERROR;
        }
    }
}
