package com.yuksabal.order.order.controller;

import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.client.dto.StockRestoreResponse;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.repository.OrderRepository;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 취소 (이슈 51). delivery, hub Feign 클라이언트는 가짜(MockBean)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderCancelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private UserClient userClient;
    @MockBean
    private CompanyClient companyClient;
    @MockBean
    private HubClient hubClient;
    @MockBean
    private DeliveryClient deliveryClient;
    @MockBean
    private SlackNotifier slackNotifier;

    private final UUID ordererId = UUID.randomUUID();
    private final UUID departureHubId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderRepository.deleteAllInBatch();
    }

    @Test
    void 확정된_주문을_취소하면_배송_취소_후_재고_복원하고_CANCELLED_보상_COMPLETED() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(departureHubId, productId, 5, order.getId())));

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("주문이 취소되었습니다."))
                .andExpect(jsonPath("$.data.orderId").value(order.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.compensationStatus").value("COMPLETED"));

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.COMPLETED);

        // 결정 19 순서: 배송 취소 → 재고 복원
        InOrder sequence = inOrder(deliveryClient, hubClient);
        sequence.verify(deliveryClient).cancelDelivery(new DeliveryCancelRequest(order.getId()));
        sequence.verify(hubClient).restoreStock(new StockRequest(departureHubId, productId, 5, order.getId()));
        verify(slackNotifier, never()).notifyManualAction(any(), anyString());
        // 주문자 본인은 company 호출 없이 통과
        verifyNoInteractions(companyClient);
    }

    // ---------- ② 권한 ----------

    @Test
    void 요청_업체_관리자는_취소할_수_있다() throws Exception {
        Order order = saveConfirmed();
        UUID ownerId = UUID.randomUUID();
        givenRequestCompanyOwner(order, ownerId);
        givenCancelSucceeds(order);

        cancel(order.getId(), ownerId, "COMPANY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void MASTER는_company_호출_없이_취소할_수_있다() throws Exception {
        Order order = saveConfirmed();
        givenCancelSucceeds(order);

        cancel(order.getId(), UUID.randomUUID(), "MASTER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verifyNoInteractions(companyClient);
    }

    @Test
    void 주문자도_요청_업체_관리자도_아니면_403이고_주문은_그대로() throws Exception {
        Order order = saveConfirmed();

        cancel(order.getId(), UUID.randomUUID(), "USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertUnchanged(order);
        // COMPANY가 아니면 company 호출 없이 403
        verifyNoInteractions(companyClient);
    }

    @Test
    void ownerId가_같아도_COMPANY가_아니면_403() throws Exception {
        Order order = saveConfirmed();
        UUID ownerId = UUID.randomUUID();
        givenRequestCompanyOwner(order, ownerId);

        // 업체 관리자였다가 역할이 바뀐 경우
        cancel(order.getId(), ownerId, "USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertUnchanged(order);
    }

    @Test
    void 다른_업체_관리자는_403() throws Exception {
        Order order = saveConfirmed();
        givenRequestCompanyOwner(order, UUID.randomUUID());

        cancel(order.getId(), UUID.randomUUID(), "COMPANY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertUnchanged(order);
    }

    @Test
    void 요청_업체가_삭제돼_404면_403() throws Exception {
        Order order = saveConfirmed();
        given(companyClient.getCompany(order.getRequestCompanyId())).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않거나 삭제된 업체입니다.", List.of()));

        cancel(order.getId(), UUID.randomUUID(), "COMPANY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertUnchanged(order);
    }

    // ---------- ③ 상태 ----------

    @Test
    void 이미_취소된_주문을_다시_취소하면_409이고_배송_취소_재고_복원_안_함() throws Exception {
        Order order = saveConfirmed();
        givenCancelSucceeds(order);
        cancel(order.getId(), ordererId, "USER").andExpect(status().isOk());

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

        // 첫 취소 때 1번씩만 호출
        verify(deliveryClient).cancelDelivery(any());
        verify(hubClient).restoreStock(any());
        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.COMPLETED);
    }

    @Test
    void 확정_전_PENDING_주문은_409() throws Exception {
        Order order = saveConfirmed();
        ReflectionTestUtils.setField(order, "status", OrderStatus.PENDING);
        orderRepository.saveAndFlush(order);

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

        verifyNoInteractions(deliveryClient, hubClient);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    // ---------- ④ 배송 취소 실패 (D-28) ----------

    @Test
    void 배송_출발_후라_delivery가_409면_CONFIRMED로_되돌리고_409_그대로() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willThrow(new FeignClientException(
                409, "INVALID_STATUS_TRANSITION", "허브를 출발한 배송은 취소할 수 없습니다.", List.of()));

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.error.message").value("허브를 출발한 배송은 취소할 수 없습니다."));

        assertReverted(order);
    }

    @Test
    void delivery_동시_수정_409_DELIVERY_CONFLICT도_되돌리고_그대로() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willThrow(new FeignClientException(
                409, "DELIVERY_CONFLICT", "다른 요청이 배송을 수정했습니다.", List.of()));

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DELIVERY_CONFLICT"));

        assertReverted(order);
        verify(deliveryClient).cancelDelivery(any());
    }

    @Test
    void delivery_5xx면_CONFIRMED로_되돌리고_502() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willThrow(
                new FeignServerException(500, "INTERNAL_SERVER_ERROR", "delivery 오류", "cancelDelivery"));

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));

        assertReverted(order);
    }

    @Test
    void 되돌린_주문은_다시_취소할_수_있다() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any()))
                .willThrow(new FeignServerException(500, "INTERNAL_SERVER_ERROR", "delivery 오류", "cancelDelivery"))
                .willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(departureHubId, productId, 5, order.getId())));
        cancel(order.getId(), ordererId, "USER").andExpect(status().isBadGateway());

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.compensationStatus").value("COMPLETED"));
    }

    @Test
    void 배송_취소_타임아웃이면_CANCELLED_유지_보상_FAILED_Slack_재고_복원_안_함_502() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willThrow(timeout());

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));

        assertCancelledAndCompensationFailed(order);
    }

    @Test
    void 배송_취소_중_예상_못한_예외도_타임아웃과_같이_처리하고_500() throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willThrow(new IllegalStateException("응답 파싱 오류"));

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isInternalServerError());

        assertCancelledAndCompensationFailed(order);
    }

    // ---------- ⑤ 재고 복원 실패 (결정 19: 취소 유지, 수동 처리) ----------

    @Test
    void 재고_복원_5xx면_취소_유지_보상_FAILED_Slack_200() throws Exception {
        assertRestoreFailure(new FeignServerException(500, "INTERNAL_SERVER_ERROR", "hub 오류", "restoreStock"));
    }

    @Test
    void 재고_복원_타임아웃이면_취소_유지_보상_FAILED_Slack_200() throws Exception {
        assertRestoreFailure(timeout());
    }

    @Test
    void 재고_복원_4xx면_취소_유지_보상_FAILED_Slack_200() throws Exception {
        assertRestoreFailure(new FeignClientException(404, "RESOURCE_NOT_FOUND", "재고가 없습니다.", List.of()));
    }

    private void assertRestoreFailure(RuntimeException restoreError) throws Exception {
        Order order = saveConfirmed();
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        given(hubClient.restoreStock(any())).willThrow(restoreError);

        cancel(order.getId(), ordererId, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.compensationStatus").value("FAILED"));

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.FAILED);
        InOrder sequence = inOrder(deliveryClient, hubClient);
        sequence.verify(deliveryClient).cancelDelivery(any());
        sequence.verify(hubClient).restoreStock(any());
        verify(slackNotifier).notifyManualAction(any(), eq("주문 취소 보상 실패 (재고 복원)"));
    }

    @Test
    void 없는_주문은_404() throws Exception {
        cancel(UUID.randomUUID(), ordererId, "USER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(companyClient, deliveryClient, hubClient);
    }

    private void givenRequestCompanyOwner(Order order, UUID ownerId) {
        given(companyClient.getCompany(order.getRequestCompanyId())).willReturn(ApiResponse.success(
                new CompanyResponse(order.getRequestCompanyId(), "요청 업체", order.getArrivalHubId(), "부산", ownerId, true)));
    }

    private void givenCancelSucceeds(Order order) {
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(departureHubId, productId, 5, order.getId())));
    }

    // delivery가 응답한 실패: CONFIRMED + NOT_REQUIRED로 되돌림, 재고 복원과 Slack 없음
    private void assertReverted(Order order) {
        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        verifyNoInteractions(hubClient, slackNotifier);
    }

    // 배송 취소 여부를 모름: CANCELLED 유지 + 보상 FAILED + Slack 수동 처리, 재고 복원 안 함
    private void assertCancelledAndCompensationFailed(Order order) {
        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.FAILED);
        verifyNoInteractions(hubClient);
        verify(slackNotifier).notifyManualAction(any(), anyString());
    }

    private RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.POST, "http://test", Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request);
    }

    // 권한 없으면 배송 취소, 재고 복원 호출 없이 CONFIRMED 그대로
    private void assertUnchanged(Order order) {
        verifyNoInteractions(deliveryClient, hubClient);
        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
    }

    private Order saveConfirmed() {
        Order order = Order.builder()
                .requestCompanyId(UUID.randomUUID())
                .supplierCompanyId(UUID.randomUUID())
                .productId(productId)
                .departureHubId(departureHubId)
                .arrivalHubId(UUID.randomUUID())
                .ordererId(ordererId)
                .quantity(5)
                .unitPrice(12_000L)
                .build();
        order.confirm(UUID.randomUUID());
        return orderRepository.saveAndFlush(order);
    }

    private ResultActions cancel(UUID orderId, UUID userId, String role) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role));
    }
}
