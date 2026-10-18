package com.yuksabal.order.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
import com.yuksabal.order.order.client.dto.DeliveryCreateRequest;
import com.yuksabal.order.order.client.dto.DeliveryCreateResponse;
import com.yuksabal.order.order.client.dto.ProductResponse;
import com.yuksabal.order.order.client.dto.StockDecreaseResponse;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.client.dto.StockRestoreResponse;
import com.yuksabal.order.order.client.dto.UserResponse;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 생성 테스트: company, hub, delivery Feign 클라이언트는 가짜(MockBean)로 대체
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderCreateIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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

    private final UUID userId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID supplierHubId = UUID.randomUUID();
    private final UUID requesterHubId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderRepository.deleteAllInBatch();
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(userId, true)));
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId)));
        given(companyClient.getCompany(supplierId)).willReturn(
                ApiResponse.success(new CompanyResponse(supplierId, "공급 업체", supplierHubId, "주소1", UUID.randomUUID())));
        given(companyClient.getCompany(requesterId)).willReturn(
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", requesterHubId, "주소2", UUID.randomUUID())));
        given(hubClient.decreaseStock(any())).willReturn(
                ApiResponse.success(new StockDecreaseResponse(supplierHubId, productId, 95, null)));
        given(deliveryClient.createDelivery(any())).willReturn(
                ApiResponse.success(new DeliveryCreateResponse(deliveryId)));
    }

    @Test
    void 정상_흐름이면_201과_CONFIRMED() throws Exception {
        String body = create(validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("주문이 생성되었습니다."))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.deliveryId").value(deliveryId.toString()))
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(objectMapper.readTree(body).path("data").path("orderId").asText());
        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        assertThat(saved.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(saved.getOrdererId()).isEqualTo(userId);
        assertThat(saved.getDepartureHubId()).isEqualTo(supplierHubId);
        assertThat(saved.getArrivalHubId()).isEqualTo(requesterHubId);
        assertThat(saved.getUnitPrice()).isEqualTo(12_000L);
        assertThat(saved.getTotalAmount()).isEqualTo(60_000L);
        assertThat(saved.getIdempotencyKey()).isEqualTo(idempotencyKey.toString());
        assertThat(saved.getOrderNumber()).matches("ORD-\\d{8}-[0-9A-F]{12}");
        assertThat(saved.getOrderNumber()).endsWith(orderId.toString().replace("-", "").substring(0, 12).toUpperCase());
    }

    @Test
    void 재고_차감과_배송_생성에_같은_orderId와_허브를_넘김() throws Exception {
        String body = create(validBody()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).path("data").path("orderId").asText());

        ArgumentCaptor<StockRequest> stock = ArgumentCaptor.forClass(StockRequest.class);
        verify(hubClient).decreaseStock(stock.capture());
        assertThat(stock.getValue()).isEqualTo(new StockRequest(supplierHubId, productId, 5, orderId));

        ArgumentCaptor<DeliveryCreateRequest> delivery = ArgumentCaptor.forClass(DeliveryCreateRequest.class);
        verify(deliveryClient).createDelivery(delivery.capture());
        assertThat(delivery.getValue()).isEqualTo(new DeliveryCreateRequest(
                orderId, supplierHubId, requesterHubId, requesterId, "부산광역시 중구 중앙대로 2", "홍길동", null));
    }

    @Test
    void 공급_업체의_상품이_아니면_400이고_저장_안함() throws Exception {
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "다른 업체 상품", 12_000L, UUID.randomUUID())));

        create(validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        assertThat(orderRepository.count()).isZero();
        verify(hubClient, never()).decreaseStock(any());
    }

    @Test
    void 상품_조회_404면_그대로_404이고_저장_안함() throws Exception {
        given(companyClient.getProduct(productId)).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않거나 삭제된 상품입니다.", List.of()));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(orderRepository.count()).isZero();
        verify(hubClient, never()).decreaseStock(any());
    }

    @Test
    void 탈퇴한_회원이면_404이고_저장_안함() throws Exception {
        given(userClient.getUser(userId)).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않거나 삭제된 회원입니다.", List.of()));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(orderRepository.count()).isZero();
        verify(companyClient, never()).getProduct(any());
    }

    @Test
    void 비활성_회원이면_403이고_저장_안함() throws Exception {
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(userId, false)));

        create(validBody())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("비활성화된 회원은 주문할 수 없습니다."));

        assertThat(orderRepository.count()).isZero();
        verify(companyClient, never()).getProduct(any());
    }

    @Test
    void 재고_부족이면_409이고_FAILED_NOT_REQUIRED() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(new FeignClientException(409, "INSUFFICIENT_STOCK",
                "주문 가능한 재고가 부족합니다.", List.of(new ApiErrorDetail("quantity", "요청 수량을 출고할 수 없습니다."))));

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        assertThat(saved.getDeliveryId()).isNull();
        verify(deliveryClient, never()).createDelivery(any());
        verify(hubClient, never()).restoreStock(any());
    }

    @Test
    void 재고_차감_404도_FAILED_NOT_REQUIRED() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "재고가 없습니다.", List.of()));

        create(validBody()).andExpect(status().isNotFound());

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        verify(deliveryClient, never()).createDelivery(any());
    }

    @Test
    void 저장_후_재확인에서_비활성이면_403이고_FAILED_NOT_REQUIRED() throws Exception {
        // 첫 확인은 활성, 저장 뒤 두 번째 확인 사이에 user가 비활성화한 상황
        given(userClient.getUser(userId)).willReturn(
                ApiResponse.success(new UserResponse(userId, true)),
                ApiResponse.success(new UserResponse(userId, false)));

        create(validBody())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        verify(hubClient, never()).decreaseStock(any());
        verify(deliveryClient, never()).createDelivery(any());
    }

    @Test
    void 저장_후_재확인에서_탈퇴면_404이고_FAILED_NOT_REQUIRED() throws Exception {
        // 첫 확인은 활성, 저장 뒤 두 번째 확인 사이에 user가 삭제한 상황
        given(userClient.getUser(userId))
                .willReturn(ApiResponse.success(new UserResponse(userId, true)))
                .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않거나 삭제된 회원입니다.", List.of()));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        verify(hubClient, never()).decreaseStock(any());
    }

    @Test
    void 배송_생성_4xx면_재고_복원하고_FAILED_보상_COMPLETED() throws Exception {
        given(deliveryClient.createDelivery(any())).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "허브 경로가 없습니다.", List.of()));
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(supplierHubId, productId, 5, null)));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.COMPLETED);
        assertThat(saved.getDeliveryId()).isNull();

        ArgumentCaptor<StockRequest> restore = ArgumentCaptor.forClass(StockRequest.class);
        verify(hubClient).restoreStock(restore.capture());
        assertThat(restore.getValue()).isEqualTo(new StockRequest(supplierHubId, productId, 5, saved.getId()));
    }

    @Test
    void 배송_생성_4xx이고_재고_복원도_실패하면_보상_FAILED() throws Exception {
        given(deliveryClient.createDelivery(any())).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "허브 경로가 없습니다.", List.of()));
        given(hubClient.restoreStock(any())).willThrow(
                new FeignClientException(422, "BUSINESS_RULE_VIOLATION", "차감한 재고와 다릅니다.", List.of()));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.FAILED);
    }

    @Test
    void 재고_차감_5xx면_502이고_재고_복원_보상_COMPLETED() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(new FeignServerException(500, "INTERNAL_SERVER_ERROR", "hub 오류", "decreaseStock"));
        givenRestoreSucceeds();

        create(validBody())
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));

        assertFailed(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        verify(deliveryClient, never()).createDelivery(any());
    }

    @Test
    void 재고_차감_타임아웃이면_502이고_재고_복원_보상_COMPLETED() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(timeout());
        givenRestoreSucceeds();

        create(validBody()).andExpect(status().isBadGateway());

        assertFailed(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        verify(deliveryClient, never()).createDelivery(any());
    }

    @Test
    void 배송_생성_5xx면_재고만_복원하고_배송_취소는_안함() throws Exception {
        given(deliveryClient.createDelivery(any())).willThrow(new FeignServerException(503, "SERVICE_UNAVAILABLE", "delivery 오류", "createDelivery"));
        givenRestoreSucceeds();

        create(validBody()).andExpect(status().isBadGateway());

        assertFailed(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        verify(deliveryClient, never()).cancelDelivery(any());
    }

    @Test
    void 배송_생성_타임아웃이면_재고_복원과_배송_취소_둘_다() throws Exception {
        given(deliveryClient.createDelivery(any())).willThrow(timeout());
        givenRestoreSucceeds();
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));

        create(validBody()).andExpect(status().isBadGateway());

        Order saved = assertFailed(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        ArgumentCaptor<DeliveryCancelRequest> cancel = ArgumentCaptor.forClass(DeliveryCancelRequest.class);
        verify(deliveryClient).cancelDelivery(cancel.capture());
        assertThat(cancel.getValue().orderId()).isEqualTo(saved.getId());
    }

    @Test
    void 배송_생성_타임아웃이고_배송_취소_실패하면_보상_FAILED() throws Exception {
        given(deliveryClient.createDelivery(any())).willThrow(timeout());
        givenRestoreSucceeds();
        given(deliveryClient.cancelDelivery(any())).willThrow(
                new FeignClientException(409, "INVALID_STATUS_TRANSITION", "허브 출발 후에는 배송을 취소할 수 없습니다.", List.of()));

        create(validBody()).andExpect(status().isBadGateway());

        assertFailed(CompensationStatus.FAILED);
        verify(hubClient).restoreStock(any());
    }

    private void givenRestoreSucceeds() {
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(supplierHubId, productId, 5, null)));
    }

    private Order assertFailed(CompensationStatus expected) {
        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(expected);
        assertThat(saved.getDeliveryId()).isNull();
        return saved;
    }

    // Feign 타임아웃과 같은 예외 (status -1 = 응답 없음)
    private RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.POST, "http://test", Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, request);
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("requestCompanyId", requesterId.toString());
        body.put("supplierCompanyId", supplierId.toString());
        body.put("productId", productId.toString());
        body.put("quantity", 5);
        body.put("requestMessage", "신선도 유지 요청");
        body.put("recipientName", "홍길동");
        body.put("deliveryAddress", "부산광역시 중구 중앙대로 2");
        return body;
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", "USER"));
    }
}
