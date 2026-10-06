package com.example.flashsale.service;

import com.example.flashsale.entity.*;
import com.example.flashsale.entity.enums.IdempotencyStatus;
import com.example.flashsale.entity.enums.OrderStatus;
import com.example.flashsale.event.InventoryReservedEvent;
import com.example.flashsale.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final UserRepository userRepository;
    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;

    @Transactional
    public void processReservation(InventoryReservedEvent event) {
        log.info("Processing reservation event: {}", event.getEventId());

        // 1. Idempotency Check
        Optional<IdempotencyRecord> existingRecord = idempotencyRecordRepository.findByIdempotencyKey(event.getIdempotencyKey());
        if (existingRecord.isPresent()) {
            if (existingRecord.get().getStatus() == IdempotencyStatus.COMPLETED) {
                log.info("Event {} already processed successfully (IdempotencyKey: {})", event.getEventId(), event.getIdempotencyKey());
                return; // EXACTLY-ONCE business effect achieved
            }
            // If PROCESSING or FAILED, we can potentially retry, but since it's in a DB transaction, 
            // if a previous attempt crashed before commit, the DB will rollback and the record won't be COMPLETED.
            // If it exists but not completed, we might update it.
        }

        User user = userRepository.findById(event.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + event.getUserId()));
        
        FlashSale flashSale = flashSaleRepository.findById(event.getFlashSaleId())
                .orElseThrow(() -> new IllegalArgumentException("Flash sale not found: " + event.getFlashSaleId()));
        
        Product product = productRepository.findById(event.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + event.getProductId()));

        // Check if order already exists just in case
        if (orderRepository.findByIdempotencyKey(event.getIdempotencyKey()).isPresent()) {
            log.info("Order with idempotency key {} already exists.", event.getIdempotencyKey());
            return;
        }

        // 2. Create Order
        Order order = Order.builder()
                .user(user)
                .flashSale(flashSale)
                .status(OrderStatus.CONFIRMED)
                .idempotencyKey(event.getIdempotencyKey())
                .build();
        
        order = orderRepository.save(order);

        // 3. Create OrderItem
        OrderItem orderItem = OrderItem.builder()
                .order(order)
                .product(product)
                .quantity(event.getQuantity())
                .unitPrice(product.getPrice()) // Using current price for simplicity
                .build();
        
        orderItemRepository.save(orderItem);

        // 4. Update Product Inventory in DB
        // We do not decrement here because we already decremented in Redis.
        // Wait, Redis is just cache/reservation. The permanent source of truth for stock is DB.
        // If we don't decrement DB here, the DB stock will remain 100 forever!
        // We MUST decrement DB stock here safely.
        product.setAvailableInventory(product.getAvailableInventory() - event.getQuantity());
        productRepository.save(product);

        // 5. Save Idempotency Record
        IdempotencyRecord record = existingRecord.orElseGet(() -> IdempotencyRecord.builder()
                .user(user)
                .idempotencyKey(event.getIdempotencyKey())
                .requestHash(event.getEventId())
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .build());
        
        record.setStatus(IdempotencyStatus.COMPLETED);
        idempotencyRecordRepository.save(record);

        log.info("Successfully processed order {} for reservation {}", order.getId(), event.getReservationId());
    }
}
