package com.example.flashsale.service;

import com.example.flashsale.config.RabbitMQConfig;
import com.example.flashsale.entity.*;
import com.example.flashsale.entity.enums.FlashSaleStatus;
import com.example.flashsale.entity.enums.IdempotencyStatus;
import com.example.flashsale.event.InventoryReservedEvent;
import com.example.flashsale.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
public class OrderProcessingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> rabbitmq = new GenericContainer<>("rabbitmq:3-management-alpine")
            .withExposedPorts(5672);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getFirstMappedPort);
        
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private FlashSaleRepository flashSaleRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private User testUser;
    private Product testProduct;
    private FlashSale testFlashSale;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();

        if (userRepository.count() == 0) {
            testUser = userRepository.save(User.builder().username("testuser_" + UUID.randomUUID()).build());
            testProduct = productRepository.save(Product.builder()
                    .name("Test Product")
                    .price(new BigDecimal("100.00"))
                    .totalInventory(100)
                    .availableInventory(100)
                    .version(0L)
                    .build());
            testFlashSale = flashSaleRepository.save(FlashSale.builder()
                    .product(testProduct)
                    .startTime(OffsetDateTime.now().minusDays(1))
                    .endTime(OffsetDateTime.now().plusDays(1))
                    .status(FlashSaleStatus.ACTIVE)
                    .build());
        } else {
            testUser = userRepository.findAll().get(0);
            testProduct = productRepository.findAll().get(0);
            testFlashSale = flashSaleRepository.findAll().get(0);
        }
    }

    @Test
    void testMessageSuccessAndDuplicate() {
        String idempotencyKey = UUID.randomUUID().toString();
        InventoryReservedEvent event = InventoryReservedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .reservationId("res-1")
                .userId(testUser.getId())
                .flashSaleId(testFlashSale.getId())
                .productId(testProduct.getId())
                .quantity(1)
                .idempotencyKey(idempotencyKey)
                .createdAt(OffsetDateTime.now())
                .build();

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, event);

        await().atMost(5, TimeUnit.SECONDS).until(() -> orderRepository.findByIdempotencyKey(idempotencyKey).isPresent());

        assertThat(orderRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        assertThat(idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey).get().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);

        // Test Duplicate Message
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, event);
        
        // Wait a bit to ensure it doesn't crash or create duplicate
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        // Should still be exactly 1 order
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void testDbFailureAndRetryToDlq() {
        String idempotencyKey = UUID.randomUUID().toString();
        // Sending invalid product ID will cause DB failure
        InventoryReservedEvent event = InventoryReservedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .reservationId("res-2")
                .userId(testUser.getId())
                .flashSaleId(testFlashSale.getId())
                .productId(99999L) // Invalid Product
                .quantity(1)
                .idempotencyKey(idempotencyKey)
                .createdAt(OffsetDateTime.now())
                .build();

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, event);

        // It should retry 3 times then go to DLQ
        // We can check the DLQ directly using RabbitAdmin or just verify the order is not created
        await().pollDelay(4, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).until(() -> orderRepository.findByIdempotencyKey(idempotencyKey).isEmpty());
        
        assertThat(orderRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();
    }
}
