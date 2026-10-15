package com.yuksabal.user.global.config;

import com.yuksabal.user.support.TestJwt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);

    @Test
    void Base64_32바이트_키는_통과하고_그대로_디코딩된다() {
        String secret = TestJwt.randomBase64Secret(32);

        JwtProperties properties = new JwtProperties(secret, ONE_HOUR);

        assertThat(properties.secretKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(secret));
    }

    @Test
    void 키가_없으면_실패() {
        assertThatThrownBy(() -> new JwtProperties(null, ONE_HOUR)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(" ", ONE_HOUR)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void Base64가_아니면_실패하고_메시지에_키가_없다() {
        String secret = "not-base64-value-with-dash-0123456789abcdef!!";

        assertThatThrownBy(() -> new JwtProperties(secret, ONE_HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .message().doesNotContain(secret);
    }

    @Test
    void 디코딩_후_32바이트_미만이면_실패() {
        assertThatThrownBy(() -> new JwtProperties(TestJwt.randomBase64Secret(31), ONE_HOUR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료_시간이_없거나_0_이하면_실패() {
        String secret = TestJwt.randomBase64Secret(32);

        assertThatThrownBy(() -> new JwtProperties(secret, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(secret, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(secret, Duration.ofSeconds(-1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toString에_키가_노출되지_않는다() {
        String secret = TestJwt.randomBase64Secret(32);

        assertThat(new JwtProperties(secret, ONE_HOUR).toString()).doesNotContain(secret);
    }
}
