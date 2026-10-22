package com.yuksabal.order.order.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 주문 이벤트 토픽을 설정한다.
 *
 * <p>주문 이벤트 처리 흐름</p>
 * <ol>
 *     <li>OrderEventTopicConfig: order.events 토픽 생성 또는 기존 토픽 사용</li>
 *     <li>주문 서비스: 주문 저장과 같은 트랜잭션에서 이벤트를 outbox(p_order_outbox)에 저장</li>
 *     <li>OrderOutboxPublisher: outbox를 주기적으로 읽어 order.events 토픽으로 발행</li>
 *     <li>OrderEventListener: 이벤트 구독 및 종류별 처리</li>
 *     <li>SlackNotifier: Slack 알림 전송</li>
 * </ol>
 */
@Configuration
public class OrderEventTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(OrderEvent.TOPIC).partitions(1).replicas(1).build();
    }
}
