package com.yuksabal.gateway.global.filter;

import com.yuksabal.gateway.global.client.UserStatus;
import com.yuksabal.gateway.global.client.UserStatusClient;
import com.yuksabal.gateway.global.client.UserStatusUnavailableException;
import com.yuksabal.gateway.global.config.AuthProperties;
import com.yuksabal.gateway.global.exception.GatewayErrorCode;
import com.yuksabal.gateway.global.jwt.AuthUser;
import com.yuksabal.gateway.global.jwt.JwtValidationException;
import com.yuksabal.gateway.global.jwt.JwtValidator;
import com.yuksabal.gateway.global.jwt.UserRole;
import com.yuksabal.gateway.global.response.ErrorResponseWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// 모든 요청의 진입점. 라우팅 전에 실행된다.
// 1. 클라이언트가 보낸 X-User-Id, X-User-Role 제거 (공개 경로 포함)
// 2. 공개 경로면 통과, 아니면 Bearer JWT 검증 → 실패 401
// 3. user-service로 회원의 현재 상태 확인 (토큰 발급 뒤 바뀐 상태를 즉시 반영)
//    탈퇴 401, 비활성 403 ACCOUNT_DISABLED, 토큰 역할 ≠ DB 역할 401 ROLE_CHANGED(재로그인), 조회 실패 503 (fail-closed)
// 4. 역할 규칙에 걸리면 403 (3을 통과했으므로 토큰 역할 = DB 역할)
// 5. 검증된 토큰 값으로 X-User-Id, X-User-Role을 설정해 하위 서비스로 전달
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticationWebFilter implements WebFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtValidator jwtValidator;
    private final UserStatusClient userStatusClient;
    private final ErrorResponseWriter errorResponseWriter;
    private final List<RequestMatcher> publicPaths;
    private final List<RoleMatcher> roleRules;

    public AuthenticationWebFilter(JwtValidator jwtValidator, UserStatusClient userStatusClient,
                                   ErrorResponseWriter errorResponseWriter, AuthProperties authProperties) {
        this.jwtValidator = jwtValidator;
        this.userStatusClient = userStatusClient;
        this.errorResponseWriter = errorResponseWriter;
        // 게이트웨이 Path 라우팅과 같은 PathPattern 문법·같은 요청 경로로 비교한다
        this.publicPaths = authProperties.publicPaths().stream()
                .map(rule -> new RequestMatcher(rule.method(), parse(rule.path())))
                .toList();
        this.roleRules = authProperties.roleRules().stream()
                .map(rule -> new RoleMatcher(new RequestMatcher(rule.method(), parse(rule.path())), rule.roles()))
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (matchesAny(publicPaths, request)) {
            return chain.filter(withUserHeaders(exchange, null));
        }

        String token = resolveToken(request);
        if (token == null) {
            return errorResponseWriter.write(exchange, GatewayErrorCode.UNAUTHORIZED);
        }

        AuthUser authUser;
        try {
            authUser = jwtValidator.validate(token);
        } catch (JwtValidationException e) {
            return errorResponseWriter.write(exchange, e.getErrorCode());
        }

        // 거절 사유를 먼저 정한 뒤 응답한다. 조회 실패 변환(onErrorReturn)을 chain.filter 앞에 두어
        // 하위 서비스 처리 중 생긴 오류까지 503으로 바뀌지 않게 한다
        return userStatusClient.fetch(authUser.userId())
                .map(status -> rejection(request, authUser, status))
                .defaultIfEmpty(Optional.of(GatewayErrorCode.UNAUTHORIZED))
                .onErrorReturn(UserStatusUnavailableException.class,
                        Optional.of(GatewayErrorCode.USER_SERVICE_UNAVAILABLE))
                .flatMap(error -> error.isPresent()
                        ? errorResponseWriter.write(exchange, error.get())
                        : chain.filter(withUserHeaders(exchange, authUser)));
    }

    // 회원 상태 → 역할 규칙 순서로 확인한다. 통과하면 빈 값
    private Optional<GatewayErrorCode> rejection(ServerHttpRequest request, AuthUser authUser, UserStatus status) {
        if (!status.active()) {
            return Optional.of(GatewayErrorCode.ACCOUNT_DISABLED);
        }
        if (!authUser.role().name().equals(status.role())) {
            return Optional.of(GatewayErrorCode.ROLE_CHANGED);
        }
        if (!isAllowed(request, authUser)) {
            return Optional.of(GatewayErrorCode.FORBIDDEN);
        }
        return Optional.empty();
    }

    // 사용자 헤더를 항상 지우고, 인증된 요청이면 토큰 값으로 다시 넣는다
    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, AuthUser authUser) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLE_HEADER);
                    if (authUser != null) {
                        headers.set(USER_ID_HEADER, authUser.userId().toString());
                        headers.set(USER_ROLE_HEADER, authUser.role().name());
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private String resolveToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    // 처음 맞는 규칙 하나만 적용. 맞는 규칙이 없으면 인증된 사용자 모두 허용
    private boolean isAllowed(ServerHttpRequest request, AuthUser authUser) {
        return roleRules.stream()
                .filter(rule -> rule.matcher().matches(request))
                .findFirst()
                .map(rule -> rule.roles().contains(authUser.role()))
                .orElse(true);
    }

    private static boolean matchesAny(List<RequestMatcher> matchers, ServerHttpRequest request) {
        return matchers.stream().anyMatch(matcher -> matcher.matches(request));
    }

    private static PathPattern parse(String path) {
        return PathPatternParser.defaultInstance.parse(path);
    }

    private record RequestMatcher(HttpMethod method, PathPattern pattern) {

        boolean matches(ServerHttpRequest request) {
            PathContainer path = request.getPath().pathWithinApplication();
            return (method == null || method.equals(request.getMethod())) && pattern.matches(path);
        }
    }

    private record RoleMatcher(RequestMatcher matcher, Set<UserRole> roles) {
    }
}
