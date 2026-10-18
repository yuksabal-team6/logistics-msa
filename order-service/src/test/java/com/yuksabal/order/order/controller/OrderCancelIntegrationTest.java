package com.yuksabal.order.order.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.CompanyClient;
import com.yuksabal.order.order.client.DeliveryClient;
import com.yuksabal.order.order.client.HubClient;
import com.yuksabal.order.order.client.UserClient;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.client.dto.StockRestoreResponse;
import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.notification.SlackNotifier;
import com.yuksabal.order.order.repository.OrderRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
