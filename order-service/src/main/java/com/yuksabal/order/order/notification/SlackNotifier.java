package com.yuksabal.order.order.notification;

import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.event.OrderEvent;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Slack Incoming Webhook을 이용해 주문 관련 알림을 전송한다.
 * <ul>
 *     <li>주문 확정: 주문 정보, 주문자 멘션을 포함한 알림 전송</li>
 *     <li>수동 처리 필요: 보상 실패 등 운영자 처리가 필요한 알림 전송</li>
 *     <li>확인 필요: 예상하지 못한 오류에 대해 확인 요청 알림 전송</li>
 * </ul>
 *
 * <p>Kafka 이벤트에 포함된 정보로 Slack block kit 메시지를 생성한다.</p>
 * <p>일반 주문 알림과 관리자 알림은 서로 다른 Webhook URL을 사용한다.
 * 관리자 URL이 없으면 기본 URL을 사용한다.</p>
 * <p>Slack 전송 실패 시 로그만 남기고 예외를 전파하지 않는다.</p>
 */
@Slf4j
@Component
public class SlackNotifier {

    // ===== 1. 상수 및 의존성 =====

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 요청사항이 너무 길면 Slack이 블록을 거절해 알림이 안 감 (section 텍스트 최대 3000자) → 잘라서 보냄
    private static final int MAX_REQUEST_MESSAGE = 500;
    // Slack 멤버 ID 형식 (user 가입 검증과 같음). 형식이 아니면 멘션하지 않음 (#221)
    private static final Pattern SLACK_MEMBER_ID = Pattern.compile("^[UW][A-Z0-9]{8,}$");

    private final RestClient slackRestClient;
    private final String webhookUrl;
    private final String adminWebhookUrl;

    public SlackNotifier(RestClient slackRestClient,
                         @Value("${slack.webhook-url:}") String webhookUrl,
                         @Value("${slack.admin-webhook-url:}") String adminWebhookUrl) {
        this.slackRestClient = slackRestClient;
        this.webhookUrl = webhookUrl;
        this.adminWebhookUrl = (adminWebhookUrl == null || adminWebhookUrl.isBlank())
                ? webhookUrl  // admin url 없으면 일반 url 사용
                : adminWebhookUrl;
    }

    // ===== 2. 기존 Order 엔티티 기반 알림 (전환 후 삭제 예정) =====

    // 주문 생성 알림 (주문 확정 커밋 후 호출)
    // 주문자 slackId가 멤버 ID 형식이면 <@slackId>로 멘션 (주문자에게 알림 푸시), 없거나 형식이 아니면 멘션 없이 전송
    // TODO #198 4단계: 호출부를 Outbox 이벤트로 바꾸면 Order를 받는 이 메서드 3개 삭제
    public void notifyOrderCreated(Order order, String productName, String supplierName, String requesterName,
                                   String ordererSlackId) {
        notifyOrderCreated(OrderEvent.orderConfirmed(order, productName, supplierName, requesterName, ordererSlackId));
    }

    // 수동 처리 필요 알림 (보상 실패 등)
    public void notifyManualAction(Order order, String reason) {
        notifyManualAction(OrderEvent.manualActionRequired(order, reason));
    }

    // 확인 필요 알림 (예상하지 못한 오류. 보상은 끝났지만 원인 확인이 필요)
    public void notifyCheckNeeded(Order order, String reason) {
        notifyCheckNeeded(OrderEvent.checkNeeded(order, reason));
    }

    // ===== 3. Kafka 이벤트 기반 Slack 알림 =====

    // 주문 접수 알림을 이벤트 값만으로 만든다 (#198, Kafka 리스너에서 호출. order DB를 읽지 않음)
    public void notifyOrderCreated(OrderEvent event) {
        // 사용자 입력이 Slack 명령(<!channel> 전체 알림, 링크)으로 해석되지 않게 escape
        String productName = escape(event.productName());
        String supplierName = escape(event.supplierName());
        String requesterName = escape(event.requesterName());
        String mention = mention(event.ordererSlackId());
        String summary = "새 주문 " + event.orderNumber() + " " + productName + " x " + event.quantity()
                + (mention == null ? "" : " " + mention);

        List<String> fieldTexts = new ArrayList<>(List.of(
                "*주문번호*\n" + event.orderNumber(),
                "*총액*\n" + String.format("%,d원", event.totalAmount()),
                "*상품*\n" + productName + " x " + event.quantity(),
                "*단가*\n" + String.format("%,d원", event.unitPrice()),
                "*공급 업체*\n" + supplierName,
                "*요청 업체*\n" + requesterName));
        if (mention != null) {
            fieldTexts.add("*주문자*\n" + mention);
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header("📦 새 주문 접수"));
        blocks.add(fields(fieldTexts.toArray(String[]::new)));
        if (event.requestMessage() != null && !event.requestMessage().isBlank()) {
            blocks.add(section("*요청사항*\n> " + escape(truncate(event.requestMessage()))));
        }
        blocks.add(divider());
        blocks.add(context("주문 ID `" + event.orderId() + "`  |  " + now()));
        send(webhookUrl, summary, blocks, event.orderId());
    }

    // 수동 처리 필요 알림을 이벤트 값만으로 만든다 (#198)
    public void notifyManualAction(OrderEvent event) {
        String summary = "[수동 처리 필요] " + event.orderNumber() + " " + event.reason();

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header("🚨 수동 처리 필요"));
        blocks.add(section("*사유*\n" + event.reason()));
        blocks.add(fields(
                "*주문번호*\n" + event.orderNumber(),
                "*주문 상태*\n`" + event.status() + "` / 보상 `" + event.compensationStatus() + "`"));
        blocks.add(divider());
        blocks.add(context("주문 ID `" + event.orderId() + "`  |  재고 복원 및 배송 취소 상태를 확인한 후 수동 처리해 주세요  |  " + now()));
        send(adminWebhookUrl, summary, blocks, event.orderId());
    }

    // 확인 필요 알림을 이벤트 값만으로 만든다 (#198)
    public void notifyCheckNeeded(OrderEvent event) {
        String summary = "[확인 필요] " + event.orderNumber() + " " + event.reason();

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header("⚠️ 확인 필요"));
        blocks.add(section("*사유*\n" + event.reason()));
        blocks.add(fields(
                "*주문번호*\n" + event.orderNumber(),
                "*주문 상태*\n`" + event.status() + "` / 보상 `" + event.compensationStatus() + "`"));
        blocks.add(divider());
        blocks.add(context("주문 ID `" + event.orderId() + "`  |  보상은 완료됐습니다. 로그에서 오류 원인을 확인해 주세요  |  " + now()));
        send(adminWebhookUrl, summary, blocks, event.orderId());
    }

    // ===== 4. Slack 전송 및 메시지 생성 유틸리티 =====
    private void send(String url, String summary, List<Map<String, Object>> blocks, UUID orderId) {
        if (url == null || url.isBlank()) {
            log.debug("[Slack] Webhook URL 미설정이라 전송 생략 orderId={}", orderId);
            return;
        }
        try {
            slackRestClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", summary, "blocks", blocks))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[Slack] 전송 성공 orderId={}", orderId);
        } catch (RuntimeException e) {
            log.warn("[Slack] 전송 실패 orderId={}, error={}", orderId, e.getClass().getSimpleName());
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

    // Slack mrkdwn 특수문자 escape (https://api.slack.com/reference/surfaces/formatting#escaping)
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // 멤버 ID 형식일 때만 멘션 문자열, 아니면 null (임의 문자열이 Slack 명령으로 해석되지 않게)
    private static String mention(String slackId) {
        return slackId != null && SLACK_MEMBER_ID.matcher(slackId).matches() ? "<@" + slackId + ">" : null;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_REQUEST_MESSAGE ? text : text.substring(0, MAX_REQUEST_MESSAGE) + "...";
    }

    private static String now() {
        return ZonedDateTime.now(KST).format(TIME);
    }
}
