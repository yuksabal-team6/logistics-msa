package com.yuksabal.order.order.notification;

import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.event.OrderEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

class SlackNotifierTest {

    private static final String WEBHOOK = "https://hooks.slack.test/services/T/B/X";
    private static final String ADMIN_WEBHOOK = "https://hooks.slack.test/services/T/B/ADMIN";

    private MockRestServiceServer server;
    private RestClient restClient;
    private final Order order = Order.builder()
            .requestCompanyId(UUID.randomUUID())
            .supplierCompanyId(UUID.randomUUID())
            .productId(UUID.randomUUID())
            .departureHubId(UUID.randomUUID())
            .arrivalHubId(UUID.randomUUID())
            .ordererId(UUID.randomUUID())
            .orderNumber("ORD-20261018-ABCDEF123456")
            .quantity(5)
            .unitPrice(12_000L)
            .requestMessage("신선도 유지 요청")
            .build();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    @Test
    void 주문_생성_알림을_Webhook으로_보냄() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text", allOf(
                        containsString("ORD-20261018-ABCDEF123456"),
                        containsString("생물 고등어 x 5"))))
                .andExpect(jsonPath("$.blocks[0].type").value("header"))
                .andExpect(jsonPath("$.blocks[0].text.text").value("📦 새 주문 접수"))
                .andExpect(jsonPath("$.blocks[1].fields[0].text", containsString("ORD-20261018-ABCDEF123456")))
                .andExpect(jsonPath("$.blocks[1].fields[1].text", containsString("60,000원")))
                .andExpect(jsonPath("$.blocks[1].fields[2].text", containsString("생물 고등어 x 5")))
                .andExpect(jsonPath("$.blocks[1].fields[4].text", containsString("공급 업체")))
                .andExpect(jsonPath("$.blocks[1].fields[5].text", containsString("요청 업체")))
                .andExpect(jsonPath("$.blocks[2].text.text", containsString("신선도 유지 요청")))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK).notifyOrderCreated(OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", null));

        server.verify();
    }

    @Test
    void 수동_처리_알림은_관리자_Webhook으로_보냄() {
        server.expect(requestTo(ADMIN_WEBHOOK))
                .andExpect(jsonPath("$.text", allOf(containsString("[수동 처리 필요]"), containsString("재고 복원 실패"))))
                .andExpect(jsonPath("$.blocks[0].text.text").value("🚨 수동 처리 필요"))
                .andExpect(jsonPath("$.blocks[1].text.text", containsString("재고 복원 실패")))
                .andExpect(jsonPath("$.blocks[4].elements[0].text", containsString("재고 복원 및 배송 취소 상태를 확인한 후 수동 처리해 주세요")))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK).notifyManualAction(OrderEvent.manualActionRequired(order, "재고 복원 실패"));

        server.verify();
    }

    @Test
    void 확인_필요_알림은_관리자_Webhook으로_보냄() {
        server.expect(requestTo(ADMIN_WEBHOOK))
                .andExpect(jsonPath("$.text", allOf(containsString("[확인 필요]"), containsString("배송 생성 중 예상하지 못한 오류"))))
                .andExpect(jsonPath("$.blocks[0].text.text").value("⚠️ 확인 필요"))
                .andExpect(jsonPath("$.blocks[4].elements[0].text", containsString("보상은 완료됐습니다. 로그에서 오류 원인을 확인해 주세요")))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK).notifyCheckNeeded(OrderEvent.checkNeeded(order, "배송 생성 중 예상하지 못한 오류"));

        server.verify();
    }

    @Test
    void 관리자_Webhook이_비어_있으면_관리자_알림도_기본_Webhook으로_보냄() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.blocks[0].text.text").value("🚨 수동 처리 필요"))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, "").notifyManualAction(OrderEvent.manualActionRequired(order, "재고 복원 실패"));

        server.verify();
    }

    @Test
    void 사용자_입력의_Slack_특수문자를_escape() {
        Order injected = Order.builder()
                .requestCompanyId(UUID.randomUUID()).supplierCompanyId(UUID.randomUUID()).productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID()).arrivalHubId(UUID.randomUUID()).ordererId(UUID.randomUUID())
                .orderNumber("ORD-20261018-ABCDEF123456").quantity(1).unitPrice(1_000L)
                .requestMessage("<!channel> 급해요 <https://evil.example|여기> & 확인")
                .build();
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.blocks[2].text.text",
                        containsString("&lt;!channel&gt; 급해요 &lt;https://evil.example|여기&gt; &amp; 확인")))
                .andExpect(jsonPath("$.blocks[1].fields[2].text", containsString("&lt;b&gt;고등어 x 1")))
                .andExpect(jsonPath("$.blocks[1].fields[4].text", containsString("A&amp;B 수산")))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK).notifyOrderCreated(OrderEvent.orderConfirmed(injected, "<b>고등어", "A&B 수산", "요청 업체", null));

        server.verify();
    }

    @Test
    void 긴_요청사항은_500자로_잘라서_보냄() {
        Order longMessage = Order.builder()
                .requestCompanyId(UUID.randomUUID()).supplierCompanyId(UUID.randomUUID()).productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID()).arrivalHubId(UUID.randomUUID()).ordererId(UUID.randomUUID())
                .orderNumber("ORD-20261018-ABCDEF123456").quantity(1).unitPrice(1_000L)
                .requestMessage("가".repeat(4000))
                .build();
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.blocks[2].text.text").value("*요청사항*\n> " + "가".repeat(500) + "..."))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK).notifyOrderCreated(OrderEvent.orderConfirmed(longMessage, "고등어", "공급 업체", "요청 업체", null));

        server.verify();
    }

    @Test
    void 주문자_slackId가_있으면_멘션() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.text", containsString("<@U12345678>")))
                .andExpect(jsonPath("$.blocks[1].fields[6].text").value("*주문자*\n<@U12345678>"))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK)
                .notifyOrderCreated(OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", "U12345678"));

        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"it-user01", "u12345678", "<!channel>", "U1234567"})
    void 주문자_slackId가_없거나_형식이_아니면_멘션_없이_보냄(String slackId) {
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.text", not(containsString("<@"))))
                .andExpect(jsonPath("$.blocks[1].fields.length()").value(6))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK)
                .notifyOrderCreated(OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", slackId));

        server.verify();
    }

    @Test
    void Webhook_URL이_없으면_보내지_않음() {
        SlackNotifier notifier = new SlackNotifier(restClient, "", "");
        notifier.notifyOrderCreated(OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", null));
        notifier.notifyManualAction(OrderEvent.manualActionRequired(order, "재고 복원 실패"));

        server.verify();
    }

    @Test
    void Slack이_실패해도_예외를_던지지_않음() {
        server.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        assertThatCode(() -> new SlackNotifier(restClient, WEBHOOK, ADMIN_WEBHOOK)
                .notifyOrderCreated(OrderEvent.orderConfirmed(order, "생물 고등어", "공급 업체", "요청 업체", null)))
                .doesNotThrowAnyException();
        server.verify();
    }
}
