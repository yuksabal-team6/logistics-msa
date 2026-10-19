package com.yuksabal.order.order.controller;

import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    // ===== 회원 진행 중 주문 존재 여부 =====

    // 주문 상태 5개 x 보상 상태 4개 = 20가지 조합 전부: 진행 중 여부는 항상 삭제 가능 여부의 반대
    static Stream<Arguments> allStatusCombinations() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(status -> Arrays.stream(CompensationStatus.values())
                        .map(compensation -> Arguments.of(status, compensation)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("allStatusCombinations")
    void 진행_중_여부는_삭제_가능_여부의_반대(OrderStatus status, CompensationStatus compensationStatus) throws Exception {
        UUID ordererId = UUID.randomUUID();
        Order order = save(ordererId, status, compensationStatus);

        inProgress(ordererId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordererId").value(ordererId.toString()))
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(!order.isDeletable()));
    }

    @Test
    void 진행_중_주문이_있으면_true와_메시지() throws Exception {
        UUID ordererId = UUID.randomUUID();
        save(ordererId, OrderStatus.COMPLETED, CompensationStatus.NOT_REQUIRED);
        save(ordererId, OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        inProgress(ordererId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("진행 중 주문 여부를 조회했습니다."))
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(true));
    }

    @Test
    void 주문이_없는_회원은_false() throws Exception {
        inProgress(UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(false));
    }

    @Test
    void 다른_회원의_진행_중_주문은_무시() throws Exception {
        UUID ordererId = UUID.randomUUID();
        save(ordererId, OrderStatus.COMPLETED, CompensationStatus.NOT_REQUIRED);
        save(UUID.randomUUID(), OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        inProgress(ordererId)
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(false));
    }

    @Test
    void 삭제된_주문은_진행_중에서_제외() throws Exception {
        UUID ordererId = UUID.randomUUID();
        Order order = save(ordererId, OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);
        order.delete(UUID.randomUUID());
        orderRepository.saveAndFlush(order);

        inProgress(ordererId)
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(false));
    }

    @Test
    void 파라미터가_없으면_400() throws Exception {
        mockMvc.perform(get("/internal/orders/in-progress"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("ordererId, productId, companyId 중 하나만 보내 주세요."));
    }

    @Test
    void 파라미터를_두_개_이상_보내면_400() throws Exception {
        mockMvc.perform(get("/internal/orders/in-progress")
                        .param("ordererId", UUID.randomUUID().toString())
                        .param("productId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 회원으로_물으면_ordererId만_응답에_있음() throws Exception {
        UUID ordererId = UUID.randomUUID();

        inProgress(ordererId)
                .andExpect(jsonPath("$.data.ordererId").value(ordererId.toString()))
                .andExpect(jsonPath("$.data.productId").doesNotExist())
                .andExpect(jsonPath("$.data.companyId").doesNotExist());
    }

    // ===== 상품, 업체 진행 중 주문 존재 여부 (company 삭제 전 확인) =====

    @Test
    void 상품의_진행_중_주문이_있으면_true() throws Exception {
        Order order = save(UUID.randomUUID(), OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        query("productId", order.getProductId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(order.getProductId().toString()))
                .andExpect(jsonPath("$.data.ordererId").doesNotExist())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(true));
    }

    @Test
    void 상품의_주문이_끝났거나_다른_상품이면_false() throws Exception {
        Order completed = save(UUID.randomUUID(), OrderStatus.COMPLETED, CompensationStatus.NOT_REQUIRED);
        save(UUID.randomUUID(), OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        query("productId", completed.getProductId())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(false));
    }

    @Test
    void 업체가_공급_업체인_진행_중_주문이_있으면_true() throws Exception {
        Order order = save(UUID.randomUUID(), OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        query("companyId", order.getSupplierCompanyId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(order.getSupplierCompanyId().toString()))
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(true));
    }

    @Test
    void 업체가_요청_업체인_진행_중_주문이_있어도_true() throws Exception {
        Order order = save(UUID.randomUUID(), OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        query("companyId", order.getRequestCompanyId())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(true));
    }

    @Test
    void 업체의_주문이_끝났거나_관계없는_업체면_false() throws Exception {
        Order completed = save(UUID.randomUUID(), OrderStatus.COMPLETED, CompensationStatus.NOT_REQUIRED);
        save(UUID.randomUUID(), OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED);

        query("companyId", completed.getSupplierCompanyId())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(false));
        query("companyId", UUID.randomUUID())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(false));
    }

    @Test
    void 보상이_안_끝난_취소_주문도_업체_진행_중() throws Exception {
        Order order = save(UUID.randomUUID(), OrderStatus.CANCELLED, CompensationStatus.FAILED);

        query("companyId", order.getRequestCompanyId())
                .andExpect(jsonPath("$.data.hasInProgressOrder").value(true));
    }

    @Test
    void productId_형식이_틀리면_400() throws Exception {
        mockMvc.perform(get("/internal/orders/in-progress").param("productId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void ordererId_형식이_틀리면_400() throws Exception {
        mockMvc.perform(get("/internal/orders/in-progress").param("ordererId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("ordererId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("형식이 올바르지 않습니다."));
    }

    private ResultActions query(String name, UUID value) throws Exception {
        return mockMvc.perform(get("/internal/orders/in-progress").param(name, value.toString()));
    }

    private ResultActions inProgress(UUID ordererId) throws Exception {
        return mockMvc.perform(get("/internal/orders/in-progress").param("ordererId", ordererId.toString()));
    }

    private Order save(UUID ordererId, OrderStatus status, CompensationStatus compensationStatus) {
        Order order = Order.builder()
                .requestCompanyId(UUID.randomUUID())
                .supplierCompanyId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .arrivalHubId(UUID.randomUUID())
                .ordererId(ordererId)
                .orderNumber("TEST-1")
                .quantity(1)
                .unitPrice(1_000L)
                .build();
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "compensationStatus", compensationStatus);
        return orderRepository.saveAndFlush(order);
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
