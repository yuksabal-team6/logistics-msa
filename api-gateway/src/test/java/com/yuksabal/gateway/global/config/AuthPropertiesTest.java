package com.yuksabal.gateway.global.config;

import com.yuksabal.gateway.global.config.AuthProperties.PathRule;
import com.yuksabal.gateway.global.config.AuthProperties.RoleRule;
import com.yuksabal.gateway.global.jwt.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPropertiesTest {

    @Test
    void 표준_메서드와_메서드_생략은_통과() {
        assertThatCode(() -> new PathRule(HttpMethod.POST, "/api/v1/auth/login")).doesNotThrowAnyException();
        assertThatCode(() -> new RoleRule(null, "/api/v1/hubs/**", Set.of(UserRole.MASTER))).doesNotThrowAnyException();
    }

    @Test
    void 메서드_오타는_실패() {
        HttpMethod typo = HttpMethod.valueOf("PSOT");

        assertThatThrownBy(() -> new RoleRule(typo, "/api/v1/hubs/**", Set.of(UserRole.MASTER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PSOT");
        assertThatThrownBy(() -> new PathRule(typo, "/api/v1/auth/login"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 경로가_비어_있으면_실패() {
        assertThatThrownBy(() -> new PathRule(HttpMethod.GET, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RoleRule(HttpMethod.GET, " ", Set.of(UserRole.MASTER)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void roles가_비어_있으면_실패() {
        assertThatThrownBy(() -> new RoleRule(HttpMethod.GET, "/api/v1/hubs/**", Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void yml_바인딩에서도_메서드_오타면_실패() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "auth.role-rules[0].method", "PSOT",
                "auth.role-rules[0].path", "/api/v1/hubs/**",
                "auth.role-rules[0].roles[0]", "MASTER")));

        assertThatThrownBy(() -> binder.bind("auth", AuthProperties.class))
                .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("PSOT");
    }

    @Test
    void yml_바인딩_정상값() {
        AuthProperties properties = bindRoleRuleMethod("DELETE");

        assertThat(properties.roleRules().get(0).method()).isEqualTo(HttpMethod.DELETE);
    }

    // 요청 메서드는 항상 대문자라 소문자 규칙은 아무 요청에도 맞지 않는다 → 오타와 같이 기동 실패
    @Test
    void yml_바인딩에서_소문자_메서드도_실패() {
        assertThatThrownBy(() -> bindRoleRuleMethod("delete"))
                .rootCause().isInstanceOf(IllegalStateException.class);
    }

    private AuthProperties bindRoleRuleMethod(String method) {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "auth.role-rules[0].method", method,
                "auth.role-rules[0].path", "/api/v1/hubs/**",
                "auth.role-rules[0].roles[0]", "MASTER")));
        return binder.bind("auth", AuthProperties.class).get();
    }
}
