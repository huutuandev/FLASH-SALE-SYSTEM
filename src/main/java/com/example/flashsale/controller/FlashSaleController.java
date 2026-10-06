package com.example.flashsale.controller;

import com.example.flashsale.dto.BuyRequest;
import com.example.flashsale.dto.BuyResponse;
import com.example.flashsale.security.RateLimitException;
import com.example.flashsale.service.FlashSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/flash-sale")
@RequiredArgsConstructor
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    @PostMapping("/buy")
    public ResponseEntity<BuyResponse> buy(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @RequestBody BuyRequest request,
            HttpServletRequest httpRequest) {
        
        String ipAddress = httpRequest.getRemoteAddr();

        BuyResponse response = flashSaleService.processBuyRequest(userId, ipAddress, request);
        
        if ("SOLD_OUT".equals(response.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        
        // HTTP 202 Accepted for async processing
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
