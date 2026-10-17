package com.yuksabal.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.feign.FeignErrorDecoder;
import com.yuksabal.common.feign.FeignExceptionHandler;
import com.yuksabal.common.feign.FeignHeaderInterceptor;
import com.yuksabal.common.feign.FeignRetryer;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

// Feign 의존성이 있는 서비스에만 헤더 전달·에러 디코더·재시도·Feign 예외 처리를 등록한다.
@AutoConfiguration
@ConditionalOnClass(name = "feign.Feign")
@Import(FeignExceptionHandler.class)
public class CommonFeignAutoConfiguration {

    @Bean
    public FeignHeaderInterceptor feignHeaderInterceptor() {
        return new FeignHeaderInterceptor();
    }

    // 서비스에서 ErrorDecoder 빈을 직접 만들면 그쪽을 사용
    @Bean
    @ConditionalOnMissingBean(ErrorDecoder.class)
    public ErrorDecoder feignErrorDecoder(ObjectMapper objectMapper) {
        return new FeignErrorDecoder(objectMapper);
    }

    // 5xx·타임아웃·연결 실패 재시도 (결정 #17). 서비스 yml의 common.feign.retry.*로 바꿀 수 있다
    // - max-retries: 처음 호출을 뺀 재시도 횟수. 0이면 재시도 안 함
    // - interval: 첫 재시도 전 대기(ms). 순간 장애(재시작 중 요청, 끊긴 커넥션)를 넘기면서 타임아웃(3s/5s)보다 충분히 작은 값
    // - max-interval: 대기 상한(ms). 대기는 1.5배씩 늘어나므로 횟수를 늘려도 이 값을 넘지 않는다
    // - retry-on-read-timeout: 응답 지연도 재시도할지. 끄면 최악 지연이 read-timeout 1번으로 줄어든다
    // 서비스에서 Retryer 빈을 직접 만들면 그쪽을 사용
    // 이때도 FeignRetryer를 써야 5xx가 FeignServerException으로 나간다.
    // 재시도를 끄려면 max-retries: 0 또는 FeignRetryer.noRetry()를 쓴다 (Retryer.NEVER_RETRY 금지)
    @Bean
    @ConditionalOnMissingBean(Retryer.class)
    public Retryer feignRetryer(
            @Value("${common.feign.retry.max-retries:2}") int maxRetries,
            @Value("${common.feign.retry.interval:200}") long interval,
            @Value("${common.feign.retry.max-interval:1000}") long maxInterval,
            @Value("${common.feign.retry.retry-on-read-timeout:true}") boolean retryOnReadTimeout) {
        return new FeignRetryer(interval, maxInterval, maxRetries + 1, retryOnReadTimeout);
    }
}
