package com.yuksabal.gateway.global.client;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

// 회원 상태 조회 결과를 회원 ID별로 잠깐 기억한다. user-service 호출을 줄이는 대신 상태 변경이 최대 ttl만큼 늦게 반영된다.
// - 성공(200) 결과만 저장한다. 없음(404·탈퇴)은 저장하지 않아 바로 반영되고,
//   조회 실패는 저장하지 않아 user-service 장애 시 캐시로 통과시키지 않는다 (fail-closed 유지)
// - 같은 회원의 동시 요청은 진행 중인 조회 하나를 함께 기다린다
public class UserStatusCache {

    private final AsyncCache<UUID, UserStatus> cache;

    public UserStatusCache(Duration ttl, long maxSize, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .ticker(ticker)
                .buildAsync();
    }

    // 결과가 비면(404) Caffeine이 항목을 지우고, 실패해도 항목을 지운다
    // 한 요청이 취소돼도 같은 조회를 기다리는 다른 요청에 영향이 없도록 조회 자체는 취소하지 않는다
    public Mono<UserStatus> get(UUID userId, Function<UUID, Mono<UserStatus>> loader) {
        return Mono.fromFuture(() -> cache.get(userId, (id, executor) -> loader.apply(id).toFuture()), true);
    }
}
