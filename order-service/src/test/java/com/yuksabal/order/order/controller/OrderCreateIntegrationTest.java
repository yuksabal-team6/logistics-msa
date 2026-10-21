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
import com.yuksabal.order.order.notification.SlackNotifier;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    // 확정 저장 실패를 재현하려고 SpyBean (평소에는 실제 TransactionTemplate과 같음)
    @SpyBean
    private TransactionTemplate transactionTemplate;

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

    private static final String ORDERER_SLACK_ID = "U0ORDERER1";

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
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(userId, true, ORDERER_SLACK_ID)));
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, true)));
        given(companyClient.getCompany(supplierId)).willReturn(
                ApiResponse.success(new CompanyResponse(supplierId, "공급 업체", supplierHubId, "주소1", UUID.randomUUID(), true)));
        given(companyClient.getCompany(requesterId)).willReturn(
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", requesterHubId, "주소2", UUID.randomUUID(), true)));
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
                orderId, supplierHubId, requesterHubId, requesterId, "부산광역시 중구 중앙대로 2", "홍길동", null,
                userId, supplierId));
    }

    @Test
    void 공급_업체의_상품이_아니면_400이고_저장_안함() throws Exception {
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "다른 업체 상품", 12_000L, UUID.randomUUID(), true)));

        create(validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        assertThat(orderRepository.count()).isZero();
        verify(hubClient, never()).decreaseStock(any());
    }

    // ---------- 비활성 상품, 업체 (#143) ----------

    @Test
    void 비활성_상품이면_422이고_저장_안함() throws Exception {
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, false)));

        assertInactiveRejected("productId", "비활성 상품은 주문할 수 없습니다.");
    }

    @Test
    void 비활성_공급_업체면_422이고_저장_안함() throws Exception {
        // 실제 company 응답처럼 공급 업체가 비활성이면 상품 active도 false. 이때 상품이 아니라 공급 업체 오류로 나가야 함
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, false)));
        given(companyClient.getCompany(supplierId)).willReturn(
                ApiResponse.success(new CompanyResponse(supplierId, "공급 업체", supplierHubId, "주소1", UUID.randomUUID(), false)));

        assertInactiveRejected("supplierCompanyId", "비활성 공급 업체의 상품은 주문할 수 없습니다.");
    }

    @Test
    void 비활성_요청_업체면_422이고_저장_안함() throws Exception {
        given(companyClient.getCompany(requesterId)).willReturn(
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", requesterHubId, "주소2", UUID.randomUUID(), false)));

        assertInactiveRejected("requestCompanyId", "비활성 업체로는 주문할 수 없습니다.");
    }

    // 주문 저장 전에 막혀서 재고 차감, 배송 생성, Slack 없음
    private void assertInactiveRejected(String field, String message) throws Exception {
        create(validBody())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(message))
                .andExpect(jsonPath("$.error.details[0].field").value(field));

        assertThat(orderRepository.count()).isZero();
        verify(hubClient, never()).decreaseStock(any());
        verify(deliveryClient, never()).createDelivery(any());
        verifyNoInteractions(slackNotifier);
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
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(userId, false, ORDERER_SLACK_ID)));

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
                ApiResponse.success(new UserResponse(userId, true, ORDERER_SLACK_ID)),
                ApiResponse.success(new UserResponse(userId, false, ORDERER_SLACK_ID)));

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
                .willReturn(ApiResponse.success(new UserResponse(userId, true, ORDERER_SLACK_ID)))
                .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않거나 삭제된 회원입니다.", List.of()));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        verify(hubClient, never()).decreaseStock(any());
    }

    // ---------- 3-2. 저장 후 상품, 업체 재확인 (업체 삭제와 경합) ----------

    @Test
    void 저장_후_재확인에서_상품_비활성이면_422이고_FAILED_NOT_REQUIRED() throws Exception {
        // 첫 확인은 활성, 저장 뒤 두 번째 확인 사이에 company가 비활성화한 상황
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, true)),
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, false)));

        create(validBody())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        assertRecheckFailed();
    }

    @Test
    void 저장_후_재확인에서_요청_업체_비활성이면_422이고_FAILED_NOT_REQUIRED() throws Exception {
        given(companyClient.getCompany(requesterId)).willReturn(
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", requesterHubId, "주소2", UUID.randomUUID(), true)),
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", requesterHubId, "주소2", UUID.randomUUID(), false)));

        create(validBody())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details[0].field").value("requestCompanyId"));

        assertRecheckFailed();
    }

    @Test
    void 저장_후_재확인에서_요청_업체가_삭제됐으면_404이고_FAILED_NOT_REQUIRED() throws Exception {
        given(companyClient.getCompany(requesterId))
                .willReturn(ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", requesterHubId, "주소2", UUID.randomUUID(), true)))
                .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않거나 삭제된 업체입니다.", List.of()));

        create(validBody())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertRecheckFailed();
    }

    @Test
    void 정상_흐름이면_저장_후_상품과_요청_업체를_한_번_더_확인() throws Exception {
        create(validBody()).andExpect(status().isCreated());

        verify(companyClient, times(2)).getProduct(productId);
        verify(companyClient, times(2)).getCompany(requesterId);
        verify(companyClient, times(1)).getCompany(supplierId);
    }

    // 재고 차감 전에 막혀서 보상 없음
    private void assertRecheckFailed() {
        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.NOT_REQUIRED);
        verify(hubClient, never()).decreaseStock(any());
        verify(deliveryClient, never()).createDelivery(any());
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

    @Test
    void 같은_키로_다시_보내면_새로_안_만들고_기존_주문으로_응답() throws Exception {
        String first = create(validBody()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(first).path("data").path("orderId").asText();

        create(validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(orderRepository.count()).isEqualTo(1);
        verify(hubClient, times(1)).decreaseStock(any());
        verify(deliveryClient, times(1)).createDelivery(any());
    }

    @Test
    void 같은_키로_다른_주문_내용을_보내면_422_IDEMPOTENCY_KEY_MISMATCH() throws Exception {
        create(validBody()).andExpect(status().isCreated());

        Map<String, Object> changed = validBody();
        changed.put("quantity", 99);
        create(changed)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_MISMATCH"));

        assertThat(orderRepository.count()).isEqualTo(1);
        verify(hubClient, times(1)).decreaseStock(any());
    }

    @Test
    void 같은_키_주문이_처리_중이면_409_ORDER_IN_PROGRESS() throws Exception {
        saveExisting(userId, OrderStatus.PENDING);

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_IN_PROGRESS"));

        verify(companyClient, never()).getProduct(any());
    }

    @Test
    void 같은_키_주문이_실패했으면_409_ORDER_ALREADY_FAILED() throws Exception {
        saveExisting(userId, OrderStatus.FAILED);

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("orderId"));

        assertThat(orderRepository.count()).isEqualTo(1);
        verify(hubClient, never()).decreaseStock(any());
    }

    @Test
    void 다른_회원이_같은_키를_보내면_409_IDEMPOTENCY_KEY_CONFLICT() throws Exception {
        saveExisting(UUID.randomUUID(), OrderStatus.CONFIRMED);

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(companyClient, never()).getProduct(any());
    }

    // 같은 Idempotency-Key로 이미 저장된 주문 (상태 지정)
    private void saveExisting(UUID ordererId, OrderStatus status) {
        Order order = Order.builder()
                .requestCompanyId(requesterId)
                .supplierCompanyId(supplierId)
                .productId(productId)
                .departureHubId(supplierHubId)
                .arrivalHubId(requesterHubId)
                .ordererId(ordererId)
                .quantity(5)
                .unitPrice(12_000L)
                .idempotencyKey(idempotencyKey.toString())
                .build();
        ReflectionTestUtils.setField(order, "status", status);
        orderRepository.saveAndFlush(order);
    }

    @Test
    void 정상_흐름이면_Slack_주문_알림을_보냄() throws Exception {
        create(validBody()).andExpect(status().isCreated());

        // 0단계 주문자 확인에서 받은 slackId를 멘션용으로 넘긴다 (#221)
        verify(slackNotifier).notifyOrderCreated(any(Order.class), eq("생물 고등어"), eq("공급 업체"), eq("요청 업체"), eq(ORDERER_SLACK_ID));
        verify(slackNotifier, never()).notifyManualAction(any(), anyString());
    }

    @Test
    void 실패한_주문은_Slack_주문_알림을_안_보냄() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(new FeignClientException(409, "INSUFFICIENT_STOCK", "재고 부족", List.of()));

        create(validBody()).andExpect(status().isConflict());

        verify(slackNotifier, never()).notifyOrderCreated(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void 보상이_실패하면_Slack_수동_처리_알림을_보냄() throws Exception {
        given(deliveryClient.createDelivery(any())).willThrow(
                new FeignClientException(404, "RESOURCE_NOT_FOUND", "허브 경로가 없습니다.", List.of()));
        given(hubClient.restoreStock(any())).willThrow(new FeignServerException(500, "INTERNAL_SERVER_ERROR", "hub 오류", "restoreStock"));

        create(validBody()).andExpect(status().isNotFound());

        verify(slackNotifier).notifyManualAction(any(Order.class), anyString());
        verify(slackNotifier, never()).notifyOrderCreated(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void 재고_차감_중_예상하지_못한_예외면_재고_복원하고_PENDING으로_안_남음() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(new IllegalStateException("응답 해석 실패"));
        givenRestoreSucceeds();

        create(validBody()).andExpect(status().isInternalServerError());

        assertFailed(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        verify(deliveryClient, never()).createDelivery(any());
        verify(slackNotifier).notifyCheckNeeded(any(Order.class), eq("재고 차감 중 예상하지 못한 오류"));
        verify(slackNotifier, never()).notifyManualAction(any(), anyString());
    }

    @Test
    void 배송_생성_응답에_data가_없으면_재고_복원과_배송_취소() throws Exception {
        given(deliveryClient.createDelivery(any())).willReturn(ApiResponse.success(null));
        givenRestoreSucceeds();
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));

        create(validBody()).andExpect(status().isInternalServerError());

        assertFailed(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        verify(deliveryClient).cancelDelivery(any());
        verify(slackNotifier).notifyCheckNeeded(any(Order.class), eq("배송 생성 중 예상하지 못한 오류"));
    }

    @Test
    void 주문_확정_저장이_실패하면_재고_복원과_배송_취소() throws Exception {
        givenRestoreSucceeds();
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        // 트랜잭션 순서: 1 PENDING 저장, 2 확정 저장, 3 실패 기록, 4 보상 기록 → 2번(확정)만 실패시킴
        doCallRealMethod().doThrow(new IllegalStateException("DB 오류")).doCallRealMethod()
                .when(transactionTemplate).execute(any());

        create(validBody()).andExpect(status().isInternalServerError());

        Order saved = orderRepository.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.COMPLETED);
        verify(hubClient).restoreStock(any());
        verify(deliveryClient).cancelDelivery(any());
        verify(slackNotifier, never()).notifyOrderCreated(any(), anyString(), anyString(), anyString(), any());
        verify(slackNotifier).notifyCheckNeeded(any(Order.class), eq("주문 확정 저장 중 예상하지 못한 오류"));
    }

    @Test
    void 확정_중_상태_전이가_거절되면_자동_보상_없이_Slack_수동_처리() throws Exception {
        // 배송 생성 중에 생성 흐름 밖에서 주문 상태가 바뀐 상황 (정상이면 일어나지 않음)
        given(deliveryClient.createDelivery(any())).willAnswer(invocation -> {
            changeStatusInDb(OrderStatus.FAILED);
            return ApiResponse.success(new DeliveryCreateResponse(deliveryId));
        });

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

        assertThat(orderRepository.findAll().get(0).getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(hubClient, never()).restoreStock(any());
        verify(deliveryClient, never()).cancelDelivery(any());
        // 확정 단계에서 바로 거절로 구분 (확정 저장 실패 경로로 가서 실패 처리를 타지 않음)
        verify(slackNotifier).notifyManualAction(any(Order.class), argThat(reason -> reason.contains("6. 주문 CONFIRM 처리")));
        verify(slackNotifier, never()).notifyCheckNeeded(any(), anyString());
    }

    @Test
    void 확정_거절_후_현재_주문_재조회가_실패해도_409_유지하고_Slack_수동_처리() throws Exception {
        given(deliveryClient.createDelivery(any())).willAnswer(invocation -> {
            changeStatusInDb(OrderStatus.FAILED);
            return ApiResponse.success(new DeliveryCreateResponse(deliveryId));
        });
        // 트랜잭션 순서: 1 PENDING 저장, 2 확정(거절), 3 거절 후 재조회 → 3번만 실패시킴
        doCallRealMethod().doCallRealMethod().doThrow(new IllegalStateException("DB 오류")).doCallRealMethod()
                .when(transactionTemplate).execute(any());

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

        verify(hubClient, never()).restoreStock(any());
        verify(deliveryClient, never()).cancelDelivery(any());
        verify(slackNotifier).notifyManualAction(any(Order.class), argThat(reason -> reason.contains("현재 상태 조회 실패")));
    }

    @Test
    void 보상이_필요한_실패_처리가_거절되면_자동_보상_없이_Slack_수동_처리() throws Exception {
        given(deliveryClient.createDelivery(any())).willAnswer(invocation -> {
            changeStatusInDb(OrderStatus.CONFIRMED);
            throw new FeignServerException(503, "SERVICE_UNAVAILABLE", "delivery 오류", "createDelivery");
        });

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

        assertThat(orderRepository.findAll().get(0).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(hubClient, never()).restoreStock(any());
        verify(slackNotifier).notifyManualAction(any(Order.class), anyString());
    }

    @Test
    void 다른_서비스를_건드리기_전_실패_처리가_거절되면_Slack_없이_로그만() throws Exception {
        given(hubClient.decreaseStock(any())).willAnswer(invocation -> {
            changeStatusInDb(OrderStatus.CONFIRMED);
            throw new FeignClientException(409, "INSUFFICIENT_STOCK", "주문 가능한 재고가 부족합니다.", List.of());
        });

        create(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

        verify(hubClient, never()).restoreStock(any());
        verify(slackNotifier, never()).notifyManualAction(any(), anyString());
    }

    @Test
    void 예상하지_못한_예외에_보상도_실패하면_수동_처리_알림만() throws Exception {
        given(hubClient.decreaseStock(any())).willThrow(new IllegalStateException("응답 해석 실패"));
        given(hubClient.restoreStock(any())).willThrow(new FeignServerException(500, "INTERNAL_SERVER_ERROR", "hub 오류", "restoreStock"));

        create(validBody()).andExpect(status().isInternalServerError());

        assertFailed(CompensationStatus.FAILED);
        verify(slackNotifier).notifyManualAction(any(Order.class), anyString());
        verify(slackNotifier, never()).notifyCheckNeeded(any(), anyString());
    }

    @Test
    void 요청사항이_500자를_넘으면_400이고_아무것도_안_함() throws Exception {
        Map<String, Object> body = validBody();
        body.put("requestMessage", "가".repeat(501));

        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("requestMessage"))
                .andExpect(jsonPath("$.error.details[0].reason").value("요청사항은 500자 이하로 입력해 주세요."));

        assertThat(orderRepository.count()).isZero();
        verify(userClient, never()).getUser(any());
        verify(slackNotifier, never()).notifyOrderCreated(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void 요청사항이_딱_500자면_통과() throws Exception {
        Map<String, Object> body = validBody();
        body.put("requestMessage", "가".repeat(500));

        create(body).andExpect(status().isCreated());
    }

    @Test
    void 수령인_100자_배송지_500자를_넘으면_400() throws Exception {
        Map<String, Object> body = validBody();
        body.put("recipientName", "가".repeat(101));
        body.put("deliveryAddress", "가".repeat(501));

        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field", org.hamcrest.Matchers.containsInAnyOrder("recipientName", "deliveryAddress")));
    }

    // 생성 흐름 도중 다른 곳에서 주문 상태를 바꾼 것처럼 DB 값을 직접 변경
    private void changeStatusInDb(OrderStatus status) {
        Order saved = orderRepository.findAll().get(0);
        ReflectionTestUtils.setField(saved, "status", status);
        orderRepository.saveAndFlush(saved);
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
