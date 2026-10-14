package com.yuksabal.gateway.global.jwt;

import com.yuksabal.gateway.global.config.JwtProperties;
import com.yuksabal.gateway.global.exception.GatewayErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

// 팀 합의 JWT 규격: HS256, sub = 사용자 UUID, role = 단일 문자열, exp·iat 필수, iss = user-service, aud 없음
@Component
public class JwtValidator {

    private static final String ALGORITHM = "HS256";
    private static final String ISSUER = "user-service";
    private static final String ROLE_CLAIM = "role";
    // UUID.fromString은 "1-1-1-1-1" 같은 축약형도 받아들이므로 표준 형식만 허용
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JwtParser parser;

    public JwtValidator(JwtProperties jwtProperties) {
        // 서명 검증 → exp 검증 → iss 검증. 서명 없는 토큰(alg=none)은 parseSignedClaims에서 거부된다.
        this.parser = Jwts.parser()
                .verifyWith(jwtProperties.secretKey())
                .requireIssuer(ISSUER)
                .build();
    }

    public AuthUser validate(String token) {
        Jws<Claims> jws;
        try {
            jws = parser.parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new JwtValidationException(GatewayErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtValidationException(GatewayErrorCode.UNAUTHORIZED);
        }

        // 같은 키로 서명한 HS384·HS512 토큰도 서명은 통과하므로 알고리즘을 따로 확인
        if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            throw new JwtValidationException(GatewayErrorCode.UNAUTHORIZED);
        }

        Claims claims = jws.getPayload();
        if (claims.getExpiration() == null || claims.getIssuedAt() == null) {
            throw new JwtValidationException(GatewayErrorCode.UNAUTHORIZED);
        }
        return new AuthUser(parseUserId(claims.getSubject()), parseRole(claims.get(ROLE_CLAIM)));
    }

    private UUID parseUserId(String subject) {
        if (subject == null || !UUID_PATTERN.matcher(subject).matches()) {
            throw new JwtValidationException(GatewayErrorCode.UNAUTHORIZED);
        }
        return UUID.fromString(subject);
    }

    private UserRole parseRole(Object role) {
        if (!(role instanceof String value)) {
            throw new JwtValidationException(GatewayErrorCode.UNAUTHORIZED);
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new JwtValidationException(GatewayErrorCode.UNAUTHORIZED);
        }
    }
}
