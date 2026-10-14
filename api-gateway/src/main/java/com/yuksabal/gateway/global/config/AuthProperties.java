package com.yuksabal.gateway.global.config;

import com.yuksabal.gateway.global.jwt.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

// application.yml의 auth.* — 인증 제외 경로와 API별 역할 제한
// 설정 오류(메서드 오타, 빈 경로)는 규칙이 조용히 무시되지 않도록 기동 시 실패시킨다.
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(List<PathRule> publicPaths, List<RoleRule> roleRules) {

    public AuthProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
    }

    // method가 없으면 모든 메서드
    public record PathRule(HttpMethod method, String path) {

        public PathRule {
            validate("auth.public-paths", method, path);
        }
    }

    public record RoleRule(HttpMethod method, String path, Set<UserRole> roles) {

        public RoleRule {
            validate("auth.role-rules", method, path);
            if (roles == null || roles.isEmpty()) {
                throw new IllegalStateException("auth.role-rules의 roles는 비어 있을 수 없습니다: " + path);
            }
            roles = Set.copyOf(roles);
        }
    }

    // HttpMethod.valueOf는 "PSOT" 같은 오타도 객체로 만들어 주므로 표준 메서드인지 직접 확인한다
    private static void validate(String property, HttpMethod method, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(property + "의 path는 비어 있을 수 없습니다.");
        }
        if (method != null && !Arrays.asList(HttpMethod.values()).contains(method)) {
            throw new IllegalStateException(property + "의 method가 올바르지 않습니다: " + method + " (" + path + ")");
        }
    }
}
