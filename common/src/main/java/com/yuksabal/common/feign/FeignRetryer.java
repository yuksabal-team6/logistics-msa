package com.yuksabal.common.feign;

import feign.RetryableException;
import feign.Retryer;

import java.net.SocketTimeoutException;
import java.util.Locale;

// 내부 API 호출 재시도 (결정 #17). Feign은 RetryableException일 때만 이 Retryer를 부른다.
// - 5xx: FeignErrorDecoder가 FeignServerException을 RetryableException으로 감싸 넘긴다 → 재시도 후에도 실패하면 FeignServerException으로 꺼내 던진다
// - 타임아웃·연결 실패: Feign이 RetryableException으로 넘긴다 → 그대로 던진다
// - 4xx: FeignClientException이라 여기로 오지 않는다 (재시도 없음)
// 횟수·간격 계산은 Retryer.Default에 맡긴다 (대기는 interval부터 1.5배씩, maxInterval까지)
// 외부 API(Slack 등)는 같은 요청이 중복될 수 있으므로 @FeignClient(configuration = ...)에 Retryer.NEVER_RETRY를 지정한다.
public class FeignRetryer implements Retryer {

    private final long interval;
    private final long maxInterval;
    private final int maxAttempts;
    private final boolean retryOnReadTimeout;
    private final Retryer delegate;

    // maxAttempts는 처음 호출을 포함한 총 횟수
    public FeignRetryer(long interval, long maxInterval, int maxAttempts, boolean retryOnReadTimeout) {
        this.interval = interval;
        this.maxInterval = maxInterval;
        this.maxAttempts = maxAttempts;
        this.retryOnReadTimeout = retryOnReadTimeout;
        this.delegate = new Retryer.Default(interval, maxInterval, maxAttempts);
    }

    @Override
    public void continueOrPropagate(RetryableException e) {
        if (!retryOnReadTimeout && isReadTimeout(e)) {
            throw e;
        }
        try {
            delegate.continueOrPropagate(e);
        } catch (RetryableException last) {
            if (last.getCause() instanceof FeignServerException serverException) {
                throw serverException;
            }
            throw last;
        }
    }

    // 호출마다 시도 횟수를 새로 세도록 Feign이 clone()으로 새 Retryer를 만든다
    @Override
    public Retryer clone() {
        return new FeignRetryer(interval, maxInterval, maxAttempts, retryOnReadTimeout);
    }

    // 기본 클라이언트(HttpURLConnection)는 연결·응답 타임아웃 모두 SocketTimeoutException이라 메시지로 구분한다
    // ("Connect timed out" / "Read timed out")
    private boolean isReadTimeout(RetryableException e) {
        if (!(e.getCause() instanceof SocketTimeoutException timeout)) {
            return false;
        }
        String message = timeout.getMessage();
        return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
    }
}
