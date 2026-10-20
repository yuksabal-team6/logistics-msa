package com.yuksabal.order.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.DeliveryCreateResponse;
import com.yuksabal.order.order.client.dto.ProductResponse;
import com.yuksabal.order.order.client.dto.StockDecreaseResponse;
import com.yuksabal.order.order.client.dto.UserResponse;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 같은 Idempotency-Key로 주문 3개가 동시에 들어와도 주문은 1개, 재고 차감, 배송 생성, Slack도 1번
// H2는 WHERE 조건 인덱스를 못 만들어서 같은 컬럼에 일반 유니크 인덱스를 만든다 (삭제 안 된 행끼리는 부분 인덱스와 같음)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderCreateConcurrencyTest {

    private static final int REQUESTS = 3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private final UUID userId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderRepository.deleteAllInBatch();
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_test_idempotency_key ON order_service.p_orders (idempotency_key)");

        // 3개 요청이 멱등성 확인을 모두 통과한 뒤(상품 조회 시점) 한꺼번에 저장으로 넘어가게 맞춘다
        CountDownLatch allArrived = new CountDownLatch(REQUESTS);
        given(companyClient.getProduct(productId)).willAnswer(invocation -> {
            allArrived.countDown();
            allArrived.await(5, TimeUnit.SECONDS);
            return ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, true));
        });
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(userId, true, "U12345678")));
        given(companyClient.getCompany(supplierId)).willReturn(
                ApiResponse.success(new CompanyResponse(supplierId, "공급 업체", UUID.randomUUID(), "주소1", UUID.randomUUID(), true)));
        given(companyClient.getCompany(requesterId)).willReturn(
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", UUID.randomUUID(), "주소2", UUID.randomUUID(), true)));
        given(hubClient.decreaseStock(any())).willReturn(
                ApiResponse.success(new StockDecreaseResponse(UUID.randomUUID(), productId, 95, null)));
        given(deliveryClient.createDelivery(any())).willReturn(
                ApiResponse.success(new DeliveryCreateResponse(UUID.randomUUID())));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS order_service.uk_test_idempotency_key");
    }

    @Test
    void 같은_키로_3개가_동시에_와도_주문은_1개() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            futures.add(pool.submit(() -> mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body()))
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")).andReturn().getResponse()));
        }
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            responses.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 주문은 1개만 저장
        assertThat(orderRepository.count()).isEqualTo(1);
        UUID orderId = orderRepository.findAll().get(0).getId();

        // 1개는 201 생성, 나머지는 먼저 저장된 주문 기준 응답 (처리 중이면 409 ORDER_IN_PROGRESS, 이미 확정됐으면 같은 주문 201)
        long created = responses.stream().filter(r -> r.getStatus() == 201).count();
        assertThat(created).isGreaterThanOrEqualTo(1);
        for (MockHttpServletResponse response : responses) {
            String json = response.getContentAsString();
            if (response.getStatus() == 201) {
                assertThat(objectMapper.readTree(json).path("data").path("orderId").asText()).isEqualTo(orderId.toString());
            } else {
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(objectMapper.readTree(json).path("error").path("code").asText()).isEqualTo("ORDER_IN_PROGRESS");
            }
        }

        // 외부 호출과 알림은 1번씩만
        verify(hubClient, times(1)).decreaseStock(any());
        verify(deliveryClient, times(1)).createDelivery(any());
        verify(slackNotifier, times(1)).notifyOrderCreated(any(), anyString(), anyString(), anyString(), any());
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("requestCompanyId", requesterId.toString());
        body.put("supplierCompanyId", supplierId.toString());
        body.put("productId", productId.toString());
        body.put("quantity", 5);
        body.put("recipientName", "홍길동");
        body.put("deliveryAddress", "부산광역시 중구 중앙대로 2");
        return body;
    }
}
