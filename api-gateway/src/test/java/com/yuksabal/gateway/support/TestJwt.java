package com.yuksabal.gateway.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

// 테스트 전용 키·토큰 생성. 실행마다 무작위 키를 만들어 실제 키를 코드에 두지 않는다.
public final class TestJwt {

    public static final String ISSUER = "user-service";

    private TestJwt() {
    }

    public static String randomBase64Secret(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public static SecretKey key(String base64Secret) {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
    }

    // 팀 합의 규격대로 채운 빌더 (HS256, sub, role, iss, iat, exp 1시간)
    public static JwtBuilder validBuilder(String subject, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)));
    }

    public static String token(String base64Secret, String subject, String role) {
        return validBuilder(subject, role).signWith(key(base64Secret), Jwts.SIG.HS256).compact();
    }
}
