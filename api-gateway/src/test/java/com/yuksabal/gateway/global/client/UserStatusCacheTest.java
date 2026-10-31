package com.yuksabal.gateway.global.client;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserStatusCacheTest {

    private static final Duration TTL = Duration.ofSeconds(5);

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final UserStatusCache cache = new UserStatusCache(TTL, 100, ticker);
    private final AtomicInteger calls = new AtomicInteger();
    private final UUID userId = UUID.randomUUID();

    @Test
    void ttl_안에서는_다시_조회하지_않는다() {
        Function<UUID, Mono<UserStatus>> loader = counting(Mono.just(new UserStatus(true, "USER")));

        assertThat(cache.get(userId, loader).block()).isEqualTo(new UserStatus(true, "USER"));
        advance(Duration.ofMillis(4999));
        assertThat(cache.get(userId, loader).block()).isEqualTo(new UserStatus(true, "USER"));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ttl이_지나면_다시_조회한다() {
        Function<UUID, Mono<UserStatus>> loader = counting(Mono.just(new UserStatus(true, "USER")));

        cache.get(userId, loader).block();
        advance(TTL);
        cache.get(userId, loader).block();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 회원별로_따로_저장한다() {
        Function<UUID, Mono<UserStatus>> loader = counting(Mono.just(new UserStatus(true, "USER")));

        cache.get(userId, loader).block();
        cache.get(UUID.randomUUID(), loader).block();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 없는_회원_결과는_저장하지_않는다() {
        Function<UUID, Mono<UserStatus>> loader = counting(Mono.empty());

        assertThat(cache.get(userId, loader).block()).isNull();
        assertThat(cache.get(userId, loader).block()).isNull();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 조회_실패는_저장하지_않고_예외_타입을_그대로_전달한다() {
        UserStatusUnavailableException failure = new UserStatusUnavailableException("user-service 호출 실패");
        Function<UUID, Mono<UserStatus>> loader = counting(Mono.error(failure));

        assertThatThrownBy(() -> cache.get(userId, loader).block()).isSameAs(failure);
        assertThatThrownBy(() -> cache.get(userId, loader).block()).isSameAs(failure);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 같은_회원의_동시_요청은_조회를_한_번만_한다() {
        Sinks.One<UserStatus> pending = Sinks.one();
        Function<UUID, Mono<UserStatus>> loader = counting(pending.asMono());

        Mono<UserStatus> first = cache.get(userId, loader);
        Mono<UserStatus> second = cache.get(userId, loader);
        var firstResult = first.toFuture();
        var secondResult = second.toFuture();
        pending.tryEmitValue(new UserStatus(true, "MASTER"));

        assertThat(firstResult.join()).isEqualTo(new UserStatus(true, "MASTER"));
        assertThat(secondResult.join()).isEqualTo(new UserStatus(true, "MASTER"));
        assertThat(calls.get()).isEqualTo(1);
    }

    private Function<UUID, Mono<UserStatus>> counting(Mono<UserStatus> result) {
        return id -> {
            calls.incrementAndGet();
            return result;
        };
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
