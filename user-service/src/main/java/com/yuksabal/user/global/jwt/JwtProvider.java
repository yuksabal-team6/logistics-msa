package com.yuksabal.user.global.jwt;

import com.yuksabal.user.global.config.JwtProperties;
import com.yuksabal.user.user.entity.UserRole;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

// 팀 합의 JWT 규격: HS256, sub = 사용자 UUID, role = 단일 문자열, exp·iat, iss = user-service, aud 없음
// api-gateway JwtValidator가 같은 규격으로 검증한다. 토큰 무효화(블랙리스트)는 하지 않는다.
@Component
public class JwtProvider {

    private static final String ISSUER = "user-service";
    private static final String ROLE_CLAIM = "role";

    private final SecretKey secretKey;
    private final Duration accessTokenExpiration;

    public JwtProvider(JwtProperties jwtProperties) {
        this.secretKey = jwtProperties.secretKey();
        this.accessTokenExpiration = jwtProperties.accessTokenExpiration();
    }

    public String createAccessToken(UUID userId, UserRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role.name())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpiration.toSeconds();
    }
}
