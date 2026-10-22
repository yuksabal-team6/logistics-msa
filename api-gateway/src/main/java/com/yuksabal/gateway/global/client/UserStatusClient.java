package com.yuksabal.gateway.global.client;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.UUID;

// 인증된 요청마다 user-service 내부 API로 회원의 현재 상태(탈퇴·활성·역할)를 조회한다.
// JWT는 1시간 유효하고 무효화 수단이 없으므로, 탈퇴·비활성화·역할 변경을 즉시 반영하려면 DB 값을 봐야 한다.
// - 200 → UserStatus
// - 404 (없거나 탈퇴한 회원) → 빈 Mono
// - 그 외 상태 코드, 연결 실패, 타임아웃, 응답 형식 오류 → UserStatusUnavailableException
@Slf4j
@Component
public class UserStatusClient {

    // Eureka 서비스 ID. 라우팅(lb://user-service)과 같은 로드밸런서로 찾는다
    private static final String BASE_URL = "http://user-service";
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    // 전역 WebClient.Builder를 @LoadBalanced로 바꾸지 않고, 이 클라이언트에만 로드밸런서 필터를 붙인다
    public UserStatusClient(WebClient.Builder webClientBuilder,
                            ReactorLoadBalancerExchangeFilterFunction loadBalancerFilter) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(RESPONSE_TIMEOUT);
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .filter(loadBalancerFilter)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Mono<UserStatus> fetch(UUID userId) {
        return webClient.get()
                .uri("/internal/users/{userId}", userId)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().then(Mono.<UserStatus>empty());
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.<UserStatus>error(new UserStatusUnavailableException(
                                "user-service 응답 상태 " + response.statusCode().value())));
                    }
                    return response.bodyToMono(InternalUserResponse.class)
                            .flatMap(body -> body.data() == null || body.data().role() == null
                                    ? Mono.<UserStatus>error(new UserStatusUnavailableException("user-service 응답 형식 오류"))
                                    : Mono.just(body.data()))
                            .switchIfEmpty(Mono.error(new UserStatusUnavailableException("user-service 응답 본문 없음")));
                })
                .onErrorMap(e -> !(e instanceof UserStatusUnavailableException),
                        e -> new UserStatusUnavailableException("user-service 호출 실패", e))
                .doOnError(e -> log.warn("User status lookup failed: userId={} ({})", userId,
                        e.getCause() == null ? e.getMessage() : e.getCause().toString()));
    }

    // user-service는 common ApiResponse로 감싸 보낸다: {"success": true, "data": {...}}
    private record InternalUserResponse(UserStatus data) {
    }
}
