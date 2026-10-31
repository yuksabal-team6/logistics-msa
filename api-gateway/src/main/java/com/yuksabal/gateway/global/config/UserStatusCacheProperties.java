package com.yuksabal.gateway.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// auth.user-status-cache: 회원 상태 조회 결과 캐시 (팀 합의 10-31: 5초, 권한 변경이 최대 5초 늦게 반영)
// ttl이 없거나 0이면 캐시를 쓰지 않는다 (요청마다 조회)
@ConfigurationProperties(prefix = "auth.user-status-cache")
public record UserStatusCacheProperties(Duration ttl, Long maxSize) {

    private static final long DEFAULT_MAX_SIZE = 10_000;

    public UserStatusCacheProperties {
        if (ttl == null) {
            ttl = Duration.ZERO;
        }
        if (ttl.isNegative()) {
            throw new IllegalStateException("auth.user-status-cache.ttl은 0 이상이어야 합니다.");
        }
        if (maxSize == null) {
            maxSize = DEFAULT_MAX_SIZE;
        }
        if (maxSize <= 0) {
            throw new IllegalStateException("auth.user-status-cache.max-size는 0보다 커야 합니다.");
        }
    }

    public boolean enabled() {
        return !ttl.isZero();
    }
}
