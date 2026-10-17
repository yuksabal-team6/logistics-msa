package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import com.yuksabal.hub.inventory.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 조건부 UPDATE·부분 유니크 인덱스가 실제로 동시 요청을 막는지는 DB가 필요해 여기서는 분기별 응답만 확인한다
@WebMvcTest(StockInternalController.class)
@Import({StockService.class, GlobalExceptionHandler.class})
class StockInternalControllerTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String USER_ID_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryRepository inventoryRepository;

    @MockBean
    private InventoryLogRepository inventoryLogRepository;

    @MockBean
    private TransactionTemplate transactionTemplate;

    // JpaAuditingConfig가 요구하는 JPA 메타모델 (WebMvcTest에는 JPA가 없다)
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @BeforeEach
    void setUp() {
        // 트랜잭션 없이 콜백만 실행한다
        given(transactionTemplate.execute(any())).willAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        given(inventoryLogRepository.findByOrderIdAndChangeType(any(UUID.class), any(InventoryChangeType.class)))
                .willReturn(Optional.empty());
    }

    @Test
    void 재고를_차감하고_DECREASE_로그를_남긴다() throws Exception {
        givenLocked(100);
        given(inventoryRepository.decreaseQuantityIfEnough(eq(SEOUL_ID), eq(PRODUCT_ID), eq(3), any(LocalDateTime.class), eq(USER_ID)))
                .willReturn(1);
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(97)));

        mockMvc.perform(decreaseRequest(decreaseBody(3)).header(USER_ID_HEADER, USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.remainingQuantity").value(97))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()));

        InventoryLog log = savedLog();
        assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.DECREASE);
        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getQuantity()).isEqualTo(3);
    }

    // Feign이 헤더를 전달하지 못한 경우 (스케줄러 등 요청 스레드가 아닌 곳에서 호출)
    @Test
    void 사용자_헤더가_없어도_차감한다() throws Exception {
        givenLocked(100);
        given(inventoryRepository.decreaseQuantityIfEnough(eq(SEOUL_ID), eq(PRODUCT_ID), eq(3), any(LocalDateTime.class), isNull()))
                .willReturn(1);
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(97)));

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingQuantity").value(97));
    }

    @Test
    void 이미_차감한_주문이면_변경_없이_현재_수량으로_성공한다() throws Exception {
        givenLocked(90);
        given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE))
                .willReturn(Optional.of(orderLog(InventoryChangeType.DECREASE, seoulInventory(90))));

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingQuantity").value(90))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()));

        then(inventoryRepository).should(never()).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
        then(inventoryLogRepository).should(never()).saveAndFlush(any(InventoryLog.class));
    }

    // 차감 요청이 타임아웃된 뒤 복원이 먼저 처리되고, 지연된 차감 요청이 늦게 도착한 경우
    @Test
    void 이미_복원된_주문이면_차감하지_않고_성공한다() throws Exception {
        givenLocked(100);
        given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.RESTORE))
                .willReturn(Optional.of(orderLog(InventoryChangeType.RESTORE, seoulInventory(100))));

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingQuantity").value(100));

        then(inventoryRepository).should(never()).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
        then(inventoryLogRepository).should(never()).saveAndFlush(any(InventoryLog.class));
    }

    @Test
    void 재고가_부족하면_409와_현재_수량을_반환한다() throws Exception {
        givenLocked(2);
        given(inventoryRepository.decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any())).willReturn(0);

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.error.message").value("재고가 부족합니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"))
                .andExpect(jsonPath("$.error.details[0].reason").value("현재 재고는 2개입니다."));

        then(inventoryLogRepository).should(never()).saveAndFlush(any(InventoryLog.class));
    }

    // 잠금 조회 단계에서 재고가 없으면 로그 확인·차감까지 가지 않는다
    @Test
    void 재고가_없으면_404를_반환한다() throws Exception {
        given(inventoryRepository.findByHubIdAndProductIdForUpdate(any(), any())).willReturn(Optional.empty());

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("해당 허브에 상품 재고가 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        then(inventoryRepository).should(never()).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
    }

    // 로그 확인 전에 재고 행을 잠가야 차감·복원이 동시에 와도 뒤에 온 요청이 앞 요청의 로그를 본다
    @Test
    void 로그를_확인하기_전에_재고_행을_잠근다() throws Exception {
        givenLocked(100);
        given(inventoryRepository.decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any())).willReturn(1);
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(97)));

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isOk());

        InOrder order = inOrder(inventoryRepository, inventoryLogRepository);
        order.verify(inventoryRepository).findByHubIdAndProductIdForUpdate(SEOUL_ID, PRODUCT_ID);
        order.verify(inventoryLogRepository).findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE);
        order.verify(inventoryLogRepository).findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.RESTORE);
        order.verify(inventoryRepository).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
    }

    // 같은 주문의 동시 요청이 둘 다 로그 확인을 통과한 경우: 늦은 쪽의 로그 INSERT가 유니크 위반 → 차감 롤백
    @Test
    void 로그_유니크_위반이면_먼저_처리된_차감_결과로_성공한다() throws Exception {
        givenLocked(100);
        given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(orderLog(InventoryChangeType.DECREASE, seoulInventory(97))));
        given(inventoryRepository.decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any())).willReturn(1);
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(94)));
        given(inventoryLogRepository.saveAndFlush(any(InventoryLog.class)))
                .willThrow(new DataIntegrityViolationException("uk_inventory_logs_order_id_change_type"));

        mockMvc.perform(decreaseRequest(decreaseBody(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingQuantity").value(97));
    }

    @Test
    void 수량이_1_미만이면_400을_반환한다() throws Exception {
        mockMvc.perform(decreaseRequest(decreaseBody(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
    }

    @Test
    void 주문_ID가_없으면_400을_반환한다() throws Exception {
        String body = """
                {"hubId": "%s", "productId": "%s", "quantity": 3}
                """.formatted(SEOUL_ID, PRODUCT_ID);

        mockMvc.perform(decreaseRequest(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("orderId"));
    }

    private MockHttpServletRequestBuilder decreaseRequest(String body) {
        return post("/internal/stocks/decrease")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String decreaseBody(int quantity) {
        return """
                {"hubId": "%s", "productId": "%s", "quantity": %d, "orderId": "%s"}
                """.formatted(SEOUL_ID, PRODUCT_ID, quantity, ORDER_ID);
    }

    private void givenLocked(int quantity) {
        given(inventoryRepository.findByHubIdAndProductIdForUpdate(SEOUL_ID, PRODUCT_ID))
                .willReturn(Optional.of(seoulInventory(quantity)));
    }

    private InventoryLog savedLog() {
        ArgumentCaptor<InventoryLog> log = ArgumentCaptor.forClass(InventoryLog.class);
        then(inventoryLogRepository).should().saveAndFlush(log.capture());
        return log.getValue();
    }

    private InventoryLog orderLog(InventoryChangeType changeType, Inventory inventory) {
        return InventoryLog.builder().inventory(inventory).orderId(ORDER_ID).changeType(changeType).quantity(3).build();
    }

    private Inventory seoulInventory(int quantity) {
        Hub seoul = Hub.builder().hubName("서울특별시 센터").address("주소").build();
        ReflectionTestUtils.setField(seoul, "id", SEOUL_ID);
        Inventory inventory = Inventory.builder().hub(seoul).productId(PRODUCT_ID).companyId(COMPANY_ID).quantity(quantity).build();
        ReflectionTestUtils.setField(inventory, "id", INVENTORY_ID);
        return inventory;
    }
}
