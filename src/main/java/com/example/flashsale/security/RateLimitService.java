package com.example.flashsale.security;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Collections;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;
    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setLocation(new ClassPathResource("scripts/rate_limiter.lua"));
        rateLimitScript.setResultType(Long.class);
    }

    public boolean isAllowed(String key, int limit, int expireSeconds) {
        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(limit),
                    String.valueOf(expireSeconds)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            // Fail-open or Fail-closed based on business. For flash sales, fail-closed or fail-open?
            // Usually we don't want to block legit users if rate limiter is slightly buggy, 
            // but for flash sale we might want to return true to allow them to hit the inventory Lua script.
            return true; 
        }
    }
}
