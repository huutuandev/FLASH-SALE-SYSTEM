package com.example.flashsale.security;

import com.example.flashsale.dto.BuyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class AntiBotService {

    private final RateLimitService rateLimitService;
    private final RiskScoreService riskScoreService;
    private final CaptchaVerificationService captchaVerificationService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${flashsale.security.hmac-secret:my-secret-key-123}")
    private String hmacSecret;

    @Value("${flashsale.security.timestamp-tolerance:30}")
    private long timestampToleranceSeconds;

    public void validateRequest(Long userId, String ipAddress, BuyRequest request) {
        // 1. Timestamp Validation
        long currentTimestamp = Instant.now().getEpochSecond();
        if (Math.abs(currentTimestamp - request.getTimestamp()) > timestampToleranceSeconds) {
            throw new SecurityException("Expired or invalid timestamp");
        }

        // 2. HMAC Signature Validation
        String expectedSignature = generateSignature(userId, request);
        if (!expectedSignature.equals(request.getSignature())) {
            throw new SecurityException("Invalid signature");
        }

        // 3. Replay Protection (Signature/Idempotency)
        // Store signature for the duration of the tolerance window * 2 to prevent replay
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(
                "replay:sig:" + request.getSignature(),
                "1",
                Duration.ofSeconds(timestampToleranceSeconds * 2)
        );
        if (Boolean.FALSE.equals(isNew)) {
            throw new SecurityException("Replay attack detected");
        }

        // 4. Risk Score & Rate Limiting
        RiskScoreService.RiskLevel riskLevel = riskScoreService.evaluateRisk(userId, ipAddress);
        if (riskLevel == RiskScoreService.RiskLevel.BOT) {
            throw new SecurityException("Bot detected");
        }

        int userLimit = (riskLevel == RiskScoreService.RiskLevel.SUSPICIOUS) ? 2 : 10;
        int ipLimit = 20;

        if (riskLevel == RiskScoreService.RiskLevel.HIGH_RISK) {
            // Require captcha (mocked check, would normally require captcha token in headers)
            if (!captchaVerificationService.verifyCaptcha("mock-token")) {
                throw new SecurityException("Captcha verification failed");
            }
        }

        if (!rateLimitService.isAllowed("rate:user:" + userId, userLimit, 1)) {
            throw new RateLimitException("User rate limit exceeded");
        }

        if (!rateLimitService.isAllowed("rate:ip:" + ipAddress, ipLimit, 1)) {
            throw new RateLimitException("IP rate limit exceeded");
        }
    }

    public String generateSignature(Long userId, BuyRequest request) {
        try {
            String payload = userId.toString() +
                    request.getFlashSaleId() +
                    request.getProductId() +
                    request.getQuantity() +
                    request.getIdempotencyKey() +
                    request.getTimestamp();

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC signature", e);
        }
    }
}
