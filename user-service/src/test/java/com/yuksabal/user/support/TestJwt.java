package com.yuksabal.user.support;

import com.yuksabal.user.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

// 테스트 전용 키 생성·토큰 파싱. 실행마다 무작위 키를 만들어 실제 키를 코드에 두지 않는다.
public final class TestJwt {

    public static final Duration EXPIRATION = Duration.ofHours(1);

    private TestJwt() {
    }

    public static String randomBase64Secret(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public static JwtProperties properties(String base64Secret) {
        return new JwtProperties(base64Secret, EXPIRATION);
    }

    // api-gateway JwtValidator와 같은 조건(서명 + iss)으로 파싱한다
    public static Jws<Claims> parse(String base64Secret, String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret)))
                .requireIssuer("user-service")
                .build()
                .parseSignedClaims(token);
    }
}
