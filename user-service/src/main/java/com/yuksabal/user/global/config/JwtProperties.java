package com.yuksabal.user.global.config;

import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;

// jwt.secret ← 환경변수 JWT_SECRET (로컬은 application-secret.yml). 표준 Base64 문자열이며 api-gateway와 같은 방식으로 디코딩한다.
// 키가 없거나 Base64가 아니거나 짧으면 기동을 실패시킨다. 오류 메시지·toString에 키 값을 넣지 않는다.
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration accessTokenExpiration) {

    // HS256 최소 키 길이 256bit
    private static final int MIN_KEY_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 환경변수가 설정되지 않았습니다.");
        }
        if (decode(secret).length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET은 디코딩 후 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다.");
        }
        // 숫자만 쓰면 밀리초 (application.yml: 3600000 = 1시간)
        if (accessTokenExpiration == null || accessTokenExpiration.isNegative() || accessTokenExpiration.isZero()) {
            throw new IllegalStateException("jwt.access-token-expiration은 0보다 커야 합니다.");
        }
    }

    public SecretKey secretKey() {
        return Keys.hmacShaKeyFor(decode(secret));
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=****, accessTokenExpiration=" + accessTokenExpiration + "]";
    }

    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            // 원인 예외 메시지에 키 일부가 담길 수 있어 연결하지 않는다
            throw new IllegalStateException("JWT_SECRET은 Base64 문자열이어야 합니다.");
        }
    }
}
