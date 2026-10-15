package com.yuksabal.order.order.controller;

import com.yuksabal.order.order.entity.CompensationStatus;
import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import com.yuksabal.order.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID companyX = UUID.randomUUID();
    private final UUID companyY = UUID.randomUUID();

    private Order orderA1;
    private Order orderA2;
    private Order orderB1;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAllInBatch();
        // A1: userA, 10/10 10:00, 60,000원, COMPLETED / A2: userA, 10/15 23:59:59, 10,000원, CONFIRMED
        // B1: userB, 10/16 00:00, 30,000원, FAILED + PENDING
        orderA1 = save(userA, companyX, "A1", 5, 12_000L, OrderStatus.COMPLETED, CompensationStatus.NOT_REQUIRED,
                LocalDateTime.of(2026, 10, 10, 10, 0));
        orderA2 = save(userA, companyX, "A2", 1, 10_000L, OrderStatus.CONFIRMED, CompensationStatus.NOT_REQUIRED,
                LocalDateTime.of(2026, 10, 15, 23, 59, 59));
        orderB1 = save(userB, companyY, "B1", 3, 10_000L, OrderStatus.FAILED, CompensationStatus.PENDING,
                LocalDateTime.of(2026, 10, 16, 0, 0));
    }

    @Test
    void 목록_MASTER는_전체를_최신순으로() throws Exception {
        perform(get(ORDERS), masterId, "MASTER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("B1", "A2", "A1")));
    }

    @Test
    void 목록_그_외_역할은_본인_주문만() throws Exception {
        perform(get(ORDERS), userA, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("A2", "A1")));
    }

    @Test
    void 목록_본인_주문만_보이는_조건은_검색_조건과_함께_적용() throws Exception {
        perform(get(ORDERS).param("requestCompanyId", companyY.toString()), userA, "COMPANY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 목록_상태와_요청_업체로_검색() throws Exception {
        perform(get(ORDERS).param("status", "FAILED"), masterId, "MASTER")
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("B1")));
        perform(get(ORDERS).param("requestCompanyId", companyX.toString()), masterId, "MASTER")
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("A2", "A1")));
    }

    @Test
    void 목록_기간은_종료일_23시59분59초까지_포함하고_다음날_0시는_제외() throws Exception {
        perform(get(ORDERS).param("createdFrom", "2026-10-15").param("createdTo", "2026-10-15"), masterId, "MASTER")
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("A2")));
        perform(get(ORDERS).param("createdFrom", "2026-10-16"), masterId, "MASTER")
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("B1")));
    }

    @Test
    void 목록_기간_형식이_다르면_400() throws Exception {
        perform(get(ORDERS).param("createdFrom", "2026/10/15"), masterId, "MASTER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 목록_금액_오름차순_정렬() throws Exception {
        perform(get(ORDERS).param("sort", "totalAmount,asc"), masterId, "MASTER")
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("A2", "B1", "A1")));
    }

    @Test
    void 목록_허용하지_않은_정렬_필드는_400() throws Exception {
        perform(get(ORDERS).param("sort", "quantity,desc"), masterId, "MASTER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));
    }

    @Test
    void 목록_페이징() throws Exception {
        perform(get(ORDERS).param("size", "2").param("page", "1"), masterId, "MASTER")
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[*].orderNumber").value(contains("A1")));
    }

    @Test
    void 상세_본인_주문은_응답_필드_포함() throws Exception {
        perform(get(ORDERS + "/" + orderA1.getId()), userA, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNumber").value("A1"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.compensationStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data.totalAmount").value(60_000));
    }

    @Test
    void 상세_남의_주문은_403() throws Exception {
        perform(get(ORDERS + "/" + orderB1.getId()), userA, "USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 헤더가_없으면_400() throws Exception {
        mockMvc.perform(get(ORDERS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("X-User-Id"));
    }

    @Test
    void 정의되지_않은_역할은_403() throws Exception {
        perform(get(ORDERS), masterId, "ABC")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 삭제_MASTER가_아니면_403() throws Exception {
        perform(delete(ORDERS + "/" + orderA1.getId()), userA, "USER")
                .andExpect(status().isForbidden());
    }

    @Test
    void 삭제_진행_중이거나_보상_미완료면_409() throws Exception {
        perform(delete(ORDERS + "/" + orderA2.getId()), masterId, "MASTER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_DELETABLE"));
        perform(delete(ORDERS + "/" + orderB1.getId()), masterId, "MASTER")
                .andExpect(status().isConflict());
    }

    @Test
    void 삭제하면_soft_delete되고_이후_조회와_삭제는_404() throws Exception {
        perform(delete(ORDERS + "/" + orderA1.getId()), masterId, "MASTER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("주문이 삭제되었습니다."));

        UUID deletedBy = jdbcTemplate.queryForObject(
                "SELECT deleted_by FROM order_service.p_orders WHERE order_id = ?", UUID.class, orderA1.getId());
        assertThat(deletedBy).isEqualTo(masterId);

        perform(get(ORDERS + "/" + orderA1.getId()), masterId, "MASTER")
                .andExpect(status().isNotFound());
        perform(delete(ORDERS + "/" + orderA1.getId()), masterId, "MASTER")
                .andExpect(status().isNotFound());
        perform(get(ORDERS), masterId, "MASTER")
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, UUID userId, String role) throws Exception {
        return mockMvc.perform(request.header("X-User-Id", userId.toString()).header("X-User-Role", role));
    }

    private Order save(UUID ordererId, UUID requestCompanyId, String orderNumber, int quantity, long unitPrice,
                       OrderStatus status, CompensationStatus compensationStatus, LocalDateTime createdAt) {
        Order order = Order.builder()
                .requestCompanyId(requestCompanyId)
                .supplierCompanyId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .departureHubId(UUID.randomUUID())
                .arrivalHubId(UUID.randomUUID())
                .ordererId(ordererId)
                .orderNumber(orderNumber)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .build();
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "compensationStatus", compensationStatus);
        Order saved = orderRepository.saveAndFlush(order);
        // created_at은 Auditing이 현재 시각으로 넣고 수정 불가라 SQL로 바꾼다
        jdbcTemplate.update("UPDATE order_service.p_orders SET created_at = ? WHERE order_id = ?", createdAt, saved.getId());
        return saved;
    }
}
