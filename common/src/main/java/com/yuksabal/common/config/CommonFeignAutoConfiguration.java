package com.yuksabal.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.feign.FeignErrorDecoder;
import com.yuksabal.common.feign.FeignExceptionHandler;
import com.yuksabal.common.feign.FeignHeaderInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

// Feign 의존성이 있는 서비스에만 헤더 전달·에러 디코더·Feign 예외 처리를 등록한다 (user-service는 제외됨).
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
}
