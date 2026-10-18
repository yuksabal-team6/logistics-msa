package com.yuksabal.order.order.notification;

import com.yuksabal.order.order.entity.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class SlackNotifierTest {

    private static final String WEBHOOK = "https://hooks.slack.test/services/T/B/X";

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

        new SlackNotifier(restClient, WEBHOOK).notifyOrderCreated(order, "생물 고등어", "공급 업체", "요청 업체");

        server.verify();
    }

    @Test
    void 수동_처리_알림을_Webhook으로_보냄() {
        server.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.text", allOf(containsString("[수동 처리 필요]"), containsString("재고 복원 실패"))))
                .andExpect(jsonPath("$.blocks[0].text.text").value("🚨 수동 처리 필요"))
                .andExpect(jsonPath("$.blocks[1].text.text", containsString("재고 복원 실패")))
                .andExpect(jsonPath("$.blocks[4].elements[0].text", containsString("재고 복원 및 배송 취소 상태를 확인한 후 수동 처리해 주세요")))
                .andRespond(withSuccess());

        new SlackNotifier(restClient, WEBHOOK).notifyManualAction(order, "재고 복원 실패");

        server.verify();
    }

    @Test
    void Webhook_URL이_없으면_보내지_않음() {
        new SlackNotifier(restClient, "").notifyOrderCreated(order, "생물 고등어", "공급 업체", "요청 업체");

        server.verify();
    }

    @Test
    void Slack이_실패해도_예외를_던지지_않음() {
        server.expect(requestTo(WEBHOOK)).andRespond(withServerError());

        assertThatCode(() -> new SlackNotifier(restClient, WEBHOOK)
                .notifyOrderCreated(order, "생물 고등어", "공급 업체", "요청 업체"))
                .doesNotThrowAnyException();
        server.verify();
    }
}
