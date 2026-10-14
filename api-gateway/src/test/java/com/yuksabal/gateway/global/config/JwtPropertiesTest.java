package com.yuksabal.gateway.global.config;

import com.yuksabal.gateway.support.TestJwt;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    void Base64_32바이트_키는_통과() {
        String secret = TestJwt.randomBase64Secret(32);

        JwtProperties properties = new JwtProperties(secret);

        assertThat(properties.secretKey().getEncoded()).isEqualTo(Base64.getDecoder().decode(secret));
    }

    @Test
    void 키가_없으면_실패() {
        assertThatThrownBy(() -> new JwtProperties(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(" ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void Base64가_아니면_실패하고_메시지에_키가_없다() {
        String secret = "not-base64-value-with-dash-0123456789abcdef!!";

        assertThatThrownBy(() -> new JwtProperties(secret))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .message().doesNotContain(secret);
    }

    @Test
    void 디코딩_후_32바이트_미만이면_실패() {
        assertThatThrownBy(() -> new JwtProperties(TestJwt.randomBase64Secret(31)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toString에_키가_노출되지_않는다() {
        String secret = TestJwt.randomBase64Secret(32);

        assertThat(new JwtProperties(secret).toString()).doesNotContain(secret);
    }
}
