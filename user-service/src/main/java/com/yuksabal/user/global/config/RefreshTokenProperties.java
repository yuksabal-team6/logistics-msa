package com.yuksabal.user.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// jwt.refresh-token.expiration: Refresh Token 유효 기간. 숫자만 쓰면 밀리초 (application.yml: 604800000 = 7일)
@ConfigurationProperties(prefix = "jwt.refresh-token")
public record RefreshTokenProperties(Duration expiration) {

    public RefreshTokenProperties {
        if (expiration == null || expiration.isNegative() || expiration.isZero()) {
            throw new IllegalStateException("jwt.refresh-token.expiration은 0보다 커야 합니다.");
        }
    }
}
