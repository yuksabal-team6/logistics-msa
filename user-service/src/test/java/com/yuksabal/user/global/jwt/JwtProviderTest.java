package com.yuksabal.user.global.jwt;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.support.TestJwt;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 팀 합의 JWT 규격: HS256, sub=회원 ID, role, iss=user-service, aud 없음
class JwtProviderTest {

    private static final long EXPIRATION_SECONDS = 3600;

    private final String secret = TestJwt.randomBase64Secret(32);
    private final JwtProvider jwtProvider = new JwtProvider(TestJwt.properties(secret));

    @Test
    void HS256으로_서명한다() {
        // when
        Jws<Claims> jws = TestJwt.parse(secret, jwtProvider.createAccessToken(UUID.randomUUID(), UserRole.COMPANY));

        // then
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    void 회원_ID_역할_발급자를_담고_대상은_비운다() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        Claims claims = issueAndParse(userId, UserRole.COMPANY);

        // then
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role")).isEqualTo("COMPANY");
        assertThat(claims.getIssuer()).isEqualTo("user-service");
        assertThat(claims.getAudience()).isNullOrEmpty();
    }

    @Test
    void 만료는_발급_시각부터_설정한_기간_뒤다() {
        // when
        Claims claims = issueAndParse(UUID.randomUUID(), UserRole.COMPANY);

        // then
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(TestJwt.EXPIRATION.toMillis());
    }

    @Test
    void 클레임은_sub_role_iss_iat_exp만_담는다() {
        // when
        Claims claims = issueAndParse(UUID.randomUUID(), UserRole.USER);

        // then
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "role", "iss", "iat", "exp");
    }

    @Test
    void 다른_키로는_검증되지_않는다() {
        // given
        String token = jwtProvider.createAccessToken(UUID.randomUUID(), UserRole.USER);
        String otherSecret = TestJwt.randomBase64Secret(32);

        // when & then
        assertThatThrownBy(() -> TestJwt.parse(otherSecret, token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void 만료_시간을_초로_알려준다() {
        // when & then
        assertThat(jwtProvider.getAccessTokenExpirationSeconds()).isEqualTo(EXPIRATION_SECONDS);
    }

    // ---------- helpers ----------

    private Claims issueAndParse(UUID userId, UserRole role) {
        return TestJwt.parse(secret, jwtProvider.createAccessToken(userId, role)).getPayload();
    }
}
