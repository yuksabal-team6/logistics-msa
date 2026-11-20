package com.yuksabal.order.order.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// OrderOutboxPublisher 스케줄러 켜기. order.outbox.enabled=false면 끔 (Kafka 없는 테스트에서 비활성화)
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "order.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OrderOutboxSchedulingConfig {
}
