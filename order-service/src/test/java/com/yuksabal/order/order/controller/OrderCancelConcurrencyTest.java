package com.yuksabal.order.order.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.StockRestoreResponse;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 같은 주문에 취소가 동시에 와도 취소는 1번만 성공, 배송 취소와 재고 복원도 1번만 호출 (1단계 행 잠금)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderCancelConcurrencyTest {

    private static final int REQUESTS = 5;

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

    private final UUID ownerId = UUID.randomUUID();
    private final UUID departureHubId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAllInBatch();
        order = Order.builder()
                .requestCompanyId(UUID.randomUUID())
                .supplierCompanyId(UUID.randomUUID())
                .productId(productId)
                .departureHubId(departureHubId)
                .arrivalHubId(UUID.randomUUID())
                .ordererId(UUID.randomUUID())
                .quantity(5)
                .unitPrice(12_000L)
                .build();
        order.confirm(UUID.randomUUID());
        order = orderRepository.saveAndFlush(order);

        // 모든 요청이 권한 확인(0단계)까지 온 뒤 한꺼번에 1단계(CANCELLED 저장)로 넘어가게 맞춘다
        CountDownLatch allArrived = new CountDownLatch(REQUESTS);
        given(companyClient.getCompany(order.getRequestCompanyId())).willAnswer(invocation -> {
            allArrived.countDown();
            allArrived.await(5, TimeUnit.SECONDS);
            return ApiResponse.success(new CompanyResponse(order.getRequestCompanyId(), "요청 업체", UUID.randomUUID(), "주소", ownerId, true));
        });
        given(deliveryClient.cancelDelivery(any())).willReturn(ApiResponse.success(null, "배송이 취소되었습니다."));
        given(hubClient.restoreStock(any())).willReturn(
                ApiResponse.success(new StockRestoreResponse(departureHubId, productId, 5, order.getId())));
    }

    @Test
    void 같은_주문을_동시에_취소해도_1번만_성공하고_외부_호출도_1번() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            futures.add(pool.submit(() -> mockMvc.perform(post("/api/v1/orders/" + order.getId() + "/cancel")
                    .header("X-User-Id", ownerId.toString())
                    .header("X-User-Role", "COMPANY")).andReturn().getResponse()));
        }
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> future : futures) {
            responses.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 1개만 200, 나머지는 이미 CANCELLED라 409
        assertThat(responses.stream().filter(r -> r.getStatus() == 200).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.getStatus() == 409).count()).isEqualTo(REQUESTS - 1);

        verify(deliveryClient, times(1)).cancelDelivery(any());
        verify(hubClient, times(1)).restoreStock(any());
        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getCompensationStatus()).isEqualTo(CompensationStatus.COMPLETED);
    }
}
