package com.yuksabal.gateway.global.jwt;

import com.yuksabal.gateway.global.config.JwtProperties;
import com.yuksabal.gateway.global.exception.GatewayErrorCode;
import com.yuksabal.gateway.support.TestJwt;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidatorTest {

    private final String secret = TestJwt.randomBase64Secret(32);
    private final JwtValidator validator = new JwtValidator(new JwtProperties(secret));
    private final String userId = UUID.randomUUID().toString();

    @Test
    void 정상_토큰이면_사용자_정보를_반환() {
        AuthUser authUser = validator.validate(TestJwt.token(secret, userId, "MASTER"));

        assertThat(authUser.userId()).isEqualTo(UUID.fromString(userId));
        assertThat(authUser.role()).isEqualTo(UserRole.MASTER);
    }

    @Test
    void 다른_키로_서명하면_거부() {
        String token = TestJwt.token(TestJwt.randomBase64Secret(32), userId, "USER");

        assertUnauthorized(token);
    }

    @Test
    void payload를_변조하면_거부() {
        String[] parts = TestJwt.token(secret, userId, "USER").split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("USER", "MASTER").getBytes());

        assertUnauthorized(parts[0] + "." + forgedPayload + "." + parts[2]);
    }

    @Test
    void 서명_없는_토큰_alg_none은_거부() {
        String token = TestJwt.validBuilder(userId, "USER").compact();

        assertUnauthorized(token);
    }

    @Test
    void HS256이_아닌_알고리즘은_같은_키여도_거부() {
        // HS512는 64바이트 이상 키가 필요하므로 64바이트 키로 검증기를 만든다
        String longSecret = TestJwt.randomBase64Secret(64);
        JwtValidator longKeyValidator = new JwtValidator(new JwtProperties(longSecret));
        String token = TestJwt.validBuilder(userId, "USER").signWith(TestJwt.key(longSecret), Jwts.SIG.HS512).compact();

        assertThatThrownBy(() -> longKeyValidator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting("errorCode").isEqualTo(GatewayErrorCode.UNAUTHORIZED);
    }

    @Test
    void 만료된_토큰은_TOKEN_EXPIRED() {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String token = TestJwt.validBuilder(userId, "USER")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.HOURS)))
                .signWith(TestJwt.key(secret), Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting("errorCode").isEqualTo(GatewayErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void iss가_다르면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(userId, "USER").issuer("other-service")));
    }

    @Test
    void iss가_없으면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(userId, "USER").issuer(null)));
    }

    @Test
    void sub가_없으면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(null, "USER")));
    }

    @Test
    void role이_없으면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(userId, null)));
    }

    @Test
    void exp가_없으면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(userId, "USER").expiration(null)));
    }

    @Test
    void iat가_없으면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(userId, "USER").issuedAt(null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", ""})
    void sub가_UUID_형식이_아니면_거부(String subject) {
        assertUnauthorized(sign(TestJwt.validBuilder(subject, "USER")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "master", "MASTER,USER", ""})
    void 허용되지_않은_role은_거부(String role) {
        assertUnauthorized(TestJwt.token(secret, userId, role));
    }

    @Test
    void role이_배열이면_거부() {
        assertUnauthorized(sign(TestJwt.validBuilder(userId, null).claim("role", new String[]{"MASTER"})));
    }

    @Test
    void 형식이_깨진_토큰은_거부() {
        assertUnauthorized("abc.def.ghi");
        assertUnauthorized("not-a-jwt");
    }

    private String sign(JwtBuilder builder) {
        return builder.signWith(TestJwt.key(secret), Jwts.SIG.HS256).compact();
    }

    private void assertUnauthorized(String token) {
        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting("errorCode").isEqualTo(GatewayErrorCode.UNAUTHORIZED);
    }
}
