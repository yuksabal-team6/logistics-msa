package com.yuksabal.order.order.notification;

import com.yuksabal.order.order.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Slack Incoming Webhook 알림 (요구사항: 메시지 저장 엔티티 없이 Webhook 호출만)
// Block Kit 형식(제목, 2열 필드, 하단 보조 정보). text는 모바일 푸시, 알림 미리보기에 쓰이는 요약
// 실패해도 예외를 던지지 않음 → 알림 실패가 주문 결과에 영향 없음. URL, slackId는 로그에 남기지 않음
@Slf4j
@Component
public class SlackNotifier {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RestClient slackRestClient;
    private final String webhookUrl;

    public SlackNotifier(RestClient slackRestClient, @Value("${slack.webhook-url:}") String webhookUrl) {
        this.slackRestClient = slackRestClient;
        this.webhookUrl = webhookUrl;
    }

    // 주문 생성 알림 (주문 확정 커밋 후 호출)
    // TODO user 내부 API 8 응답에 slackId가 추가되면 주문자 멘션(<@slackId>) 붙이기
    public void notifyOrderCreated(Order order, String productName, String supplierName, String requesterName) {
        String summary = "새 주문 " + order.getOrderNumber() + " " + productName + " x " + order.getQuantity();

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header("📦 새 주문 접수"));
        blocks.add(fields(
                "*주문번호*\n" + order.getOrderNumber(),
                "*총액*\n" + String.format("%,d원", order.getTotalAmount()),
                "*상품*\n" + productName + " x " + order.getQuantity(),
                "*단가*\n" + String.format("%,d원", order.getUnitPrice()),
                "*공급 업체*\n" + supplierName,
                "*요청 업체*\n" + requesterName));
        if (order.getRequestMessage() != null && !order.getRequestMessage().isBlank()) {
            blocks.add(section("*요청사항*\n> " + order.getRequestMessage()));
        }
        blocks.add(divider());
        blocks.add(context("주문 ID `" + order.getId() + "`  |  " + now()));
        send(summary, blocks, order);
    }

    // 수동 처리 필요 알림 (보상 실패 등)
    public void notifyManualAction(Order order, String reason) {
        String summary = "[수동 처리 필요] " + order.getOrderNumber() + " " + reason;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header("🚨 수동 처리 필요"));
        blocks.add(section("*사유*\n" + reason));
        blocks.add(fields(
                "*주문번호*\n" + order.getOrderNumber(),
                "*주문 상태*\n`" + order.getStatus() + "` / 보상 `" + order.getCompensationStatus() + "`"));
        blocks.add(divider());
        blocks.add(context("주문 ID `" + order.getId() + "`  |  재고 복원, 배송 취소 여부를 확인한 뒤 처리해 주세요  |  " + now()));
        send(summary, blocks, order);
    }

    private void send(String summary, List<Map<String, Object>> blocks, Order order) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("[Slack] Webhook URL 미설정이라 전송 생략 orderId={}", order.getId());
            return;
        }
        try {
            slackRestClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", summary, "blocks", blocks))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[Slack] 전송 성공 orderId={}", order.getId());
        } catch (RuntimeException e) {
            log.warn("[Slack] 전송 실패 orderId={}, error={}", order.getId(), e.getClass().getSimpleName());
        }
    }

    // Block Kit 블록 (https://api.slack.com/block-kit)
    private static Map<String, Object> header(String text) {
        return Map.of("type", "header", "text", Map.of("type", "plain_text", "text", text, "emoji", true));
    }

    private static Map<String, Object> section(String markdown) {
        return Map.of("type", "section", "text", mrkdwn(markdown));
    }

    // 2열 필드 (최대 10개)
    private static Map<String, Object> fields(String... markdowns) {
        return Map.of("type", "section", "fields", Arrays.stream(markdowns).map(SlackNotifier::mrkdwn).toList());
    }

    private static Map<String, Object> context(String markdown) {
        return Map.of("type", "context", "elements", List.of(mrkdwn(markdown)));
    }

    private static Map<String, Object> divider() {
        return Map.of("type", "divider");
    }

    private static Map<String, Object> mrkdwn(String text) {
        return Map.of("type", "mrkdwn", "text", text);
    }

    private static String now() {
        return ZonedDateTime.now(KST).format(TIME);
    }
}
