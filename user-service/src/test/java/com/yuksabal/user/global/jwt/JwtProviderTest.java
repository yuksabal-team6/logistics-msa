package com.yuksabal.user.global.jwt;

import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private final String secret = TestJwt.randomBase64Secret(32);
    private final JwtProvider jwtProvider = new JwtProvider(TestJwt.properties(secret));

    @Test
    void 팀_합의_규격대로_발급한다() {
        UUID userId = UUID.randomUUID();

        Jws<Claims> jws = TestJwt.parse(secret, jwtProvider.createAccessToken(userId, UserRole.COMPANY));
        Claims claims = jws.getPayload();

        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role")).isEqualTo("COMPANY");
        assertThat(claims.getIssuer()).isEqualTo("user-service");
        assertThat(claims.getAudience()).isNullOrEmpty();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(TestJwt.EXPIRATION.toMillis());
    }

    @Test
    void 클레임은_sub_role_iss_iat_exp만_담는다() {
        Claims claims = TestJwt.parse(secret, jwtProvider.createAccessToken(UUID.randomUUID(), UserRole.USER)).getPayload();

        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "role", "iss", "iat", "exp");
    }

    @Test
    void 다른_키로는_검증되지_않는다() {
        String token = jwtProvider.createAccessToken(UUID.randomUUID(), UserRole.USER);

        assertThatThrownBy(() -> TestJwt.parse(TestJwt.randomBase64Secret(32), token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void 만료_시간을_초로_알려준다() {
        assertThat(jwtProvider.getAccessTokenExpirationSeconds()).isEqualTo(3600);
    }
}
