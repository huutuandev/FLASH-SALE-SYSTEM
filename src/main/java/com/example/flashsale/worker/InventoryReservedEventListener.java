package com.example.flashsale.worker;

import com.example.flashsale.config.RabbitMQConfig;
import com.example.flashsale.event.InventoryReservedEvent;
import com.example.flashsale.service.OrderProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservedEventListener {

    private final OrderProcessingService orderProcessingService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void onInventoryReserved(@Payload InventoryReservedEvent event) {
        log.info("Received InventoryReservedEvent from RabbitMQ: {}", event.getEventId());
        
        try {
            orderProcessingService.processReservation(event);
            log.info("Successfully processed InventoryReservedEvent: {}", event.getEventId());
        } catch (Exception e) {
            log.error("Failed to process InventoryReservedEvent: {}. Error: {}", event.getEventId(), e.getMessage());
            throw e; // Throwing exception triggers Spring AMQP retry mechanism
        }
    }
}
