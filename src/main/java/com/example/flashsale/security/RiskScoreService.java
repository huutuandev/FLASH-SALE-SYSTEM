package com.example.flashsale.security;

import org.springframework.stereotype.Service;

@Service
public class RiskScoreService {
    
    public enum RiskLevel {
        NORMAL, SUSPICIOUS, HIGH_RISK, BOT
    }
    
    public RiskLevel evaluateRisk(Long userId, String ipAddress) {
        // Abstraction: In a real world, this could query ML models or Cloudflare Turnstile scores
        return RiskLevel.NORMAL;
    }
}
