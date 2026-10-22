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
import com.yuksabal.order.order.client.dto.StockRestoreResponse;
import com.yuksabal.order.order.client.dto.UserResponse;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 하나가 DB 커넥션을 2개 이상 동시에 쥐지 않는지 확인 (#274)
// 커넥션 풀을 1개로 줄이면, 요청이 첫 커넥션을 쥔 채 두 번째를 기다리는 구조일 때 동시 요청 없이 1건만으로도 실패함
// (실제 환경: 풀 10개에 동시 요청 10개면 전부 서로를 기다리다 30초 뒤 커넥션 타임아웃)
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=2000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderConnectionPoolTest {

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
    @MockBean
    private SlackNotifier slackNotifier;

    private final UUID userId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID hubId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderRepository.deleteAllInBatch();
    }

    @Test
    void 커넥션이_1개뿐이어도_주문_생성이_성공() throws Exception {
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(userId, true, null)));
        given(companyClient.getProduct(productId)).willReturn(
                ApiResponse.success(new ProductResponse(productId, "생물 고등어", 12_000L, supplierId, true)));
        given(companyClient.getCompany(supplierId)).willReturn(
                ApiResponse.success(new CompanyResponse(supplierId, "공급 업체", hubId, "주소1", UUID.randomUUID(), true)));
        given(companyClient.getCompany(requesterId)).willReturn(
                ApiResponse.success(new CompanyResponse(requesterId, "요청 업체", hubId, "주소2", UUID.randomUUID(), true)));
        given(hubClient.decreaseStock(any())).willReturn(
                ApiResponse.success(new StockDecreaseResponse(hubId, productId, 95, null)));
        given(deliveryClient.createDelivery(any())).willReturn(
                ApiResponse.success(new DeliveryCreateResponse(UUID.randomUUID())));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestCompanyId", requesterId.toString(),
                                "supplierCompanyId", supplierId.toString(),
                                "productId", productId.toString(),
                                "quantity", 5,
                                "recipientName", "홍길동",
                                "deliveryAddress", "부산광역시 중구 중앙대로 2")))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void 커넥션이_1개뿐이어도_주문_취소가_성공() throws Exception {
        Order order = Order.builder()
                .requestCompanyId(requesterId).supplierCompanyId(supplierId).productId(productId)
                .departureHubId(hubId).arrivalHubId(hubId).ordererId(userId)
                .quantity(5).unitPrice(12_000L)
                .build();
        order.confirm(UUID.randomUUID());
        orderRepository.saveAndFlush(order);
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(hubId, productId, 5, order.getId())));

        mockMvc.perform(post("/api/v1/orders/" + order.getId() + "/cancel")
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
