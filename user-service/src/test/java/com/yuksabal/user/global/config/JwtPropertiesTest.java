package com.yuksabal.user.global.config;

import com.yuksabal.user.support.TestJwt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final int MIN_KEY_BYTES = 32;

    @Test
    void Base64_32바이트_키는_통과하고_그대로_디코딩된다() {
        // given
        String secret = TestJwt.randomBase64Secret(MIN_KEY_BYTES);

        // when
        JwtProperties properties = new JwtProperties(secret, ONE_HOUR);

        // then
        assertThat(properties.secretKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(secret));
    }

    @Test
    void 키가_null이면_실패() {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(null, ONE_HOUR)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_공백이면_실패() {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(" ", ONE_HOUR)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void Base64가_아니면_실패하고_메시지에_키가_없다() {
        // given
        String notBase64Secret = "not-base64-value-with-dash-0123456789abcdef!!";

        // when & then
        assertThatThrownBy(() -> new JwtProperties(notBase64Secret, ONE_HOUR))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .message().doesNotContain(notBase64Secret);
    }

    @Test
    void 디코딩_후_32바이트_미만이면_실패() {
        // given
        String shortSecret = TestJwt.randomBase64Secret(MIN_KEY_BYTES - 1);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(shortSecret, ONE_HOUR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료_시간이_null이면_실패() {
        // given
        String secret = TestJwt.randomBase64Secret(MIN_KEY_BYTES);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(secret, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료_시간이_0이면_실패() {
        // given
        String secret = TestJwt.randomBase64Secret(MIN_KEY_BYTES);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(secret, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료_시간이_음수면_실패() {
        // given
        String secret = TestJwt.randomBase64Secret(MIN_KEY_BYTES);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(secret, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toString에_키가_노출되지_않는다() {
        // given
        String secret = TestJwt.randomBase64Secret(MIN_KEY_BYTES);

        // when
        String text = new JwtProperties(secret, ONE_HOUR).toString();

        // then
        assertThat(text).doesNotContain(secret);
    }
}
