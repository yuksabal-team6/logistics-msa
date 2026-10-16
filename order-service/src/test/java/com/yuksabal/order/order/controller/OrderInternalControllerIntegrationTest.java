package com.yuksabal.order.order.controller;

import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내부 API #10 배송 완료 → 주문 COMPLETED. 헤더 없이 호출 (사용자 권한 검사 없음)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderInternalControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void clean() {
        orderRepository.deleteAllInBatch();
    }

    @Test
    void CONFIRMED_주문은_COMPLETED로_변경() throws Exception {
        Order order = save(OrderStatus.CONFIRMED);

        complete(order.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("주문이 완료 처리되었습니다."))
                .andExpect(jsonPath("$.data.orderId").value(order.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void 같은_요청을_두_번_보내도_200() throws Exception {
        Order order = save(OrderStatus.CONFIRMED);

        complete(order.getId()).andExpect(status().isOk());
        complete(order.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void 취소된_주문은_409() throws Exception {
        Order order = save(OrderStatus.CANCELLED);

        complete(order.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.error.message").value("배송 중인 주문만 완료 처리할 수 있습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("orderId"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 없는_주문은_404() throws Exception {
        complete(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[0].field").value("orderId"));
    }

    @Test
    void 삭제된_주문은_404() throws Exception {
        Order order = save(OrderStatus.CONFIRMED);
        order.delete(UUID.randomUUID());
        orderRepository.saveAndFlush(order);

        complete(order.getId()).andExpect(status().isNotFound());
    }

    @Test
    void orderId_형식이_틀리면_400() throws Exception {
        mockMvc.perform(post("/internal/orders/abc/complete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("orderId"));
    }

    private ResultActions complete(UUID orderId) throws Exception {
        return mockMvc.perform(post("/internal/orders/" + orderId + "/complete"));
    }

    private Order save(OrderStatus status) {
        Order order = Order.builder()
                .requestCompanyId(UUID.randomUUID())
                .supplierCompanyId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .arrivalHubId(UUID.randomUUID())
                .ordererId(UUID.randomUUID())
                .orderNumber("TEST-1")
                .quantity(1)
                .unitPrice(1_000L)
                .build();
        ReflectionTestUtils.setField(order, "status", status);
        return orderRepository.saveAndFlush(order);
    }
}
