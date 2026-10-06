package com.example.flashsale.security;

import org.springframework.stereotype.Service;

@Service
public class CaptchaVerificationService {
    public boolean verifyCaptcha(String captchaToken) {
        // Abstraction: Verify with Google reCAPTCHA or Cloudflare Turnstile
        return true;
    }
}
