package com.example.flashsale.controller;

import com.example.flashsale.dto.BuyRequest;
import com.example.flashsale.security.AntiBotService;
import com.example.flashsale.service.RedisInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class FlashSaleApiControllerIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AntiBotService antiBotService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Long userId = 1L;
    private Long flashSaleId = 1L;
    private Long productId = 100L;
    
    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        redisTemplate.opsForValue().set("flash-sale:1:inventory", "100");
    }

    private BuyRequest createRequest() {
        String idempotencyKey = UUID.randomUUID().toString();
        long timestamp = Instant.now().getEpochSecond();
        BuyRequest request = BuyRequest.builder()
                .flashSaleId(flashSaleId)
                .productId(productId)
                .quantity(1)
                .idempotencyKey(idempotencyKey)
                .timestamp(timestamp)
                .build();
        
        request.setSignature(antiBotService.generateSignature(userId, request));
        return request;
    }

    @Test
    void testValidRequest_ShouldReturn202() throws Exception {
        BuyRequest request = createRequest();
        
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void testInvalidSignature_ShouldReturn401() throws Exception {
        BuyRequest request = createRequest();
        request.setSignature("invalid-signature");
        
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testExpiredTimestamp_ShouldReturn401() throws Exception {
        BuyRequest request = createRequest();
        request.setTimestamp(Instant.now().getEpochSecond() - 100); // 100 seconds ago
        request.setSignature(antiBotService.generateSignature(userId, request)); // resign with old timestamp
        
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testReplayRequest_ShouldReturn401() throws Exception {
        BuyRequest request = createRequest();
        String json = objectMapper.writeValueAsString(request);
        
        // First request is accepted
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isAccepted());

        // Second request (replay exact payload) is rejected
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testRateLimit_ShouldReturn429() throws Exception {
        // Send 11 valid requests for the same user (limit is 10)
        for (int i = 0; i < 11; i++) {
            BuyRequest request = createRequest();
            var result = mockMvc.perform(post("/api/v1/flash-sale/buy")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));
            
            if (i < 10) {
                result.andExpect(status().isAccepted());
            } else {
                result.andExpect(status().isTooManyRequests());
            }
        }
    }

    @Test
    void testSoldOut_ShouldReturn409() throws Exception {
        // Set inventory to 0
        redisTemplate.opsForValue().set("flash-sale:1:inventory", "0");
        
        BuyRequest request = createRequest();
        
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("SOLD_OUT"));
    }

    @Test
    void testDuplicateIdempotencyRequest_ShouldReturn202() throws Exception {
        BuyRequest request = createRequest();
        
        // First request is accepted
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
                
        // Create a new request with the SAME idempotencyKey but NEW timestamp/signature
        BuyRequest duplicateRequest = createRequest();
        duplicateRequest.setIdempotencyKey(request.getIdempotencyKey()); // reuse idempotency key
        duplicateRequest.setSignature(antiBotService.generateSignature(userId, duplicateRequest)); // valid signature
        
        mockMvc.perform(post("/api/v1/flash-sale/buy")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }
}
