package com.yuksabal.it.support;

import com.yuksabal.it.support.Gateway.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

// 요청 여러 개를 동시에 출발시킨다 (동시 요청 시나리오용)
//
// 요청마다 스레드를 하나씩 두고, 모든 스레드가 준비될 때까지 기다렸다가 한꺼번에 출발시킨다
// → 차례로 보내면 앞 요청이 끝난 뒤 뒤 요청이 출발해 경합이 생기지 않는다
// 토큰은 출발 전에 미리 받아 두고 넘긴다 (로그인이 요청 안에 있으면 출발 시각이 어긋난다)
//
// 결과는 넘긴 순서 그대로 돌려준다. 어느 쪽이 먼저 처리될지는 매번 달라서,
// 테스트는 "몇 번째가 성공했는지"가 아니라 "성공 몇 건, 실패 몇 건"과 최종 데이터를 확인한다
public final class Concurrently {

    private Concurrently() {
    }

    @SafeVarargs
    public static List<Response> run(Supplier<Response>... requests) {
        return run(List.of(requests));
    }

    public static List<Response> run(List<Supplier<Response>> requests) {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (Supplier<Response> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.get();
                }));
            }
            ready.await();
            start.countDown();

            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("동시 요청 중 오류", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    // 응답 중 상태 코드가 status인 것의 수 (예: 200 한 건, 409 한 건인지 확인)
    public static long count(List<Response> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }
}
