package com.yuksabal.order.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// Slack Incoming Webhook 전용 RestClient. Feign이 아니라 common 재시도에 안 걸림 (재시도하면 같은 메시지가 여러 번 감)
@Configuration
public class SlackConfig {

    @Bean
    public RestClient slackRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).build();
    }
}
