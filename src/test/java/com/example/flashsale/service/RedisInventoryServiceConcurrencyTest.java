package com.example.flashsale.service;

import com.example.flashsale.config.RedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisConfig.class, RedisInventoryService.class, RedisAutoConfiguration.class})
@Testcontainers
public class RedisInventoryServiceConcurrencyTest {

    @Container
    public static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisInventoryService redisInventoryService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final Long FLASH_SALE_ID = 1L;

    @BeforeEach
    void setUp() {
        redisTemplate.opsForValue().set("flash-sale:" + FLASH_SALE_ID + ":inventory", "100");
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void testConcurrentReservations() throws InterruptedException {
        int threadCount = 150;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final long userId = i + 1;
            final String idempotencyKey = UUID.randomUUID().toString();
            
            executorService.submit(() -> {
                try {
                    ReservationResult result = redisInventoryService.reserveInventory(FLASH_SALE_ID, userId, 1, idempotencyKey);
                    if (result == ReservationResult.SUCCESS) {
                        successCount.incrementAndGet();
                    } else if (result == ReservationResult.SOLD_OUT) {
                        soldOutCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(100);
        assertThat(soldOutCount.get()).isEqualTo(50);

        String remainingStock = (String) redisTemplate.opsForValue().get("flash-sale:" + FLASH_SALE_ID + ":inventory");
        assertThat(remainingStock).isEqualTo("0");
    }

    @Test
    void testDuplicateIdempotencyKey() {
        String idempotencyKey = UUID.randomUUID().toString();
        
        ReservationResult first = redisInventoryService.reserveInventory(FLASH_SALE_ID, 1L, 1, idempotencyKey);
        assertThat(first).isEqualTo(ReservationResult.SUCCESS);

        ReservationResult second = redisInventoryService.reserveInventory(FLASH_SALE_ID, 2L, 1, idempotencyKey);
        assertThat(second).isEqualTo(ReservationResult.DUPLICATE_REQUEST);
    }

    @Test
    void testUserAlreadyReserved() {
        Long userId = 99L;
        
        ReservationResult first = redisInventoryService.reserveInventory(FLASH_SALE_ID, userId, 1, UUID.randomUUID().toString());
        assertThat(first).isEqualTo(ReservationResult.SUCCESS);

        ReservationResult second = redisInventoryService.reserveInventory(FLASH_SALE_ID, userId, 1, UUID.randomUUID().toString());
        assertThat(second).isEqualTo(ReservationResult.USER_ALREADY_RESERVED);
    }
}
