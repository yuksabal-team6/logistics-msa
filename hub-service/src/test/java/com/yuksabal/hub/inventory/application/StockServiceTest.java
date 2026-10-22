package com.yuksabal.hub.inventory.application;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.hub.inventory.presentation.dto.request.StockRequest;
import com.yuksabal.hub.inventory.presentation.dto.response.StockDecreaseResponse;
import com.yuksabal.hub.inventory.presentation.dto.response.StockRestoreResponse;
import com.yuksabal.hub.inventory.domain.Inventory;
import com.yuksabal.hub.inventory.domain.InventoryChangeType;
import com.yuksabal.hub.inventory.domain.InventoryLog;
import com.yuksabal.hub.inventory.domain.InventoryLogRepository;
import com.yuksabal.hub.inventory.domain.InventoryRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.hub.global.exception.HubErrorCode.INSUFFICIENT_STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

// 주문 재고 차감·복원 (내부 API #3, #4). Repository·TransactionTemplate은 가짜로 두고, 검증 순서와 예외 코드만 확인한다
// 행 잠금·조건부 UPDATE·(order_id, change_type) 부분 유니크 인덱스의 실제 동작은 여기서 확인하지 않는다
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID BUSAN_INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000004");
    private static final UUID ORDER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryLogRepository inventoryLogRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private StockService stockService;

    @Nested
    class 차감 {

        @Test
        void 재고가_없으면_404이고_로그를_확인하거나_차감하지_않는다() {
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductIdForUpdate(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> stockService.decreaseStock(request(3), USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                    });

            verifyNoInteractions(inventoryLogRepository);
            then(inventoryRepository).should(never()).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
        }

        // 로그 확인 뒤에 잠그면 그 사이에 복원이 끼어들 수 있다
        @Test
        void 로그를_확인하기_전에_재고_행을_잠근다() {
            givenTransactionRuns();
            givenLocked(seoul(100));
            givenNoLog(InventoryChangeType.DECREASE);
            givenNoLog(InventoryChangeType.RESTORE);
            givenDecreased(1);
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoul(97)));

            stockService.decreaseStock(request(3), USER_ID);

            InOrder order = inOrder(inventoryRepository, inventoryLogRepository);
            order.verify(inventoryRepository).findByHubIdAndProductIdForUpdate(SEOUL_ID, PRODUCT_ID);
            order.verify(inventoryLogRepository).findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE);
            order.verify(inventoryRepository).decreaseQuantityIfEnough(eq(SEOUL_ID), eq(PRODUCT_ID), eq(3), any(LocalDateTime.class), eq(USER_ID));
        }

        @Test
        void 차감하고_주문_ID와_차감_수량으로_DECREASE_로그를_남긴다() {
            givenTransactionRuns();
            givenLocked(seoul(100));
            givenNoLog(InventoryChangeType.DECREASE);
            givenNoLog(InventoryChangeType.RESTORE);
            givenDecreased(1);
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoul(97)));

            StockDecreaseResponse response = stockService.decreaseStock(request(3), USER_ID);

            assertThat(response.remainingQuantity()).isEqualTo(97);
            InventoryLog log = savedLog();
            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.DECREASE);
            assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(log.getQuantity()).isEqualTo(3);
        }

        @Test
        void 이미_차감한_주문이면_차감하지_않고_현재_수량으로_성공한다() {
            givenTransactionRuns();
            givenLocked(seoul(97));
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE))
                    .willReturn(Optional.of(log(seoul(97), InventoryChangeType.DECREASE, 3)));

            StockDecreaseResponse response = stockService.decreaseStock(request(3), USER_ID);

            assertThat(response.remainingQuantity()).isEqualTo(97);
            then(inventoryRepository).should(never()).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
            then(inventoryLogRepository).should(never()).saveAndFlush(any());
        }

        // 차감 요청이 타임아웃된 뒤 order가 복원을 보냈고, 지연된 차감이 그보다 늦게 도착한 경우
        @Test
        void 이미_복원된_주문이면_차감하지_않는다() {
            givenTransactionRuns();
            givenLocked(seoul(100));
            givenNoLog(InventoryChangeType.DECREASE);
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.RESTORE))
                    .willReturn(Optional.of(log(seoul(100), InventoryChangeType.RESTORE, 0)));

            StockDecreaseResponse response = stockService.decreaseStock(request(3), USER_ID);

            assertThat(response.remainingQuantity()).isEqualTo(100);
            then(inventoryRepository).should(never()).decreaseQuantityIfEnough(any(), any(), anyInt(), any(), any());
            then(inventoryLogRepository).should(never()).saveAndFlush(any());
        }

        @Test
        void 재고가_부족하면_409와_현재_수량이고_로그를_남기지_않는다() {
            givenTransactionRuns();
            givenLocked(seoul(2));
            givenNoLog(InventoryChangeType.DECREASE);
            givenNoLog(InventoryChangeType.RESTORE);
            givenDecreased(0);

            assertThatThrownBy(() -> stockService.decreaseStock(request(3), USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(INSUFFICIENT_STOCK);
                        assertThat(e.getDetails()).extracting("reason").containsExactly("현재 재고는 2개입니다.");
                    });

            then(inventoryLogRepository).should(never()).saveAndFlush(any());
        }

        // 같은 주문의 동시 차감: 늦은 쪽은 DECREASE 로그 유니크 위반으로 롤백되고 먼저 커밋된 결과로 응답한다
        @Test
        void 유니크_위반이면_먼저_처리된_차감_결과로_성공한다() {
            given(transactionTemplate.execute(any())).willThrow(new DataIntegrityViolationException("duplicate key"));
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE))
                    .willReturn(Optional.of(log(seoul(97), InventoryChangeType.DECREASE, 3)));

            StockDecreaseResponse response = stockService.decreaseStock(request(3), USER_ID);

            assertThat(response.remainingQuantity()).isEqualTo(97);
            assertThat(response.orderId()).isEqualTo(ORDER_ID);
        }

        @Test
        void 유니크_위반인데_차감_로그가_없으면_원래_예외를_던진다() {
            DataIntegrityViolationException otherViolation = new DataIntegrityViolationException("other constraint");
            given(transactionTemplate.execute(any())).willThrow(otherViolation);
            givenNoLog(InventoryChangeType.DECREASE);

            assertThatThrownBy(() -> stockService.decreaseStock(request(3), USER_ID))
                    .isSameAs(otherViolation);
        }
    }

    @Nested
    class 복원 {

        @Test
        void 재고가_없으면_404이고_로그를_확인하거나_복원하지_않는다() {
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductIdForUpdate(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> stockService.restoreStock(request(3), USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));

            verifyNoInteractions(inventoryLogRepository);
            then(inventoryRepository).should(never()).increaseQuantity(any(), anyInt(), any(), any());
        }

        // 요청 수량이 아니라 DECREASE 로그의 수량만큼 되돌린다
        @Test
        void 차감한_수량만큼_복원하고_RESTORE_로그를_남긴다() {
            givenTransactionRuns();
            Inventory locked = seoul(97);
            givenLocked(locked);
            givenNoLog(InventoryChangeType.RESTORE);
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE))
                    .willReturn(Optional.of(log(locked, InventoryChangeType.DECREASE, 3)));
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoul(100)));

            StockRestoreResponse response = stockService.restoreStock(request(5), USER_ID);

            assertThat(response.restoredQuantity()).isEqualTo(3);
            then(inventoryRepository).should().increaseQuantity(eq(INVENTORY_ID), eq(3), any(LocalDateTime.class), eq(USER_ID));
            InventoryLog log = savedLog();
            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.RESTORE);
            assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(log.getQuantity()).isEqualTo(3);
        }

        @Test
        void 이미_복원한_주문이면_변경_없이_처음_복원한_수량으로_성공한다() {
            givenTransactionRuns();
            givenLocked(seoul(100));
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.RESTORE))
                    .willReturn(Optional.of(log(seoul(100), InventoryChangeType.RESTORE, 3)));

            StockRestoreResponse response = stockService.restoreStock(request(3), USER_ID);

            assertThat(response.restoredQuantity()).isEqualTo(3);
            then(inventoryRepository).should(never()).increaseQuantity(any(), anyInt(), any(), any());
            then(inventoryLogRepository).should(never()).saveAndFlush(any());
        }

        // 차감 요청이 타임아웃돼 실제로는 차감되지 않은 경우. 이 로그가 늦게 도착한 차감을 막는다
        @Test
        void 차감_기록이_없으면_재고는_그대로_두고_수량_0인_RESTORE_로그를_남긴다() {
            givenTransactionRuns();
            givenLocked(seoul(100));
            givenNoLog(InventoryChangeType.RESTORE);
            givenNoLog(InventoryChangeType.DECREASE);

            StockRestoreResponse response = stockService.restoreStock(request(3), USER_ID);

            assertThat(response.restoredQuantity()).isZero();
            then(inventoryRepository).should(never()).increaseQuantity(any(), anyInt(), any(), any());
            InventoryLog log = savedLog();
            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.RESTORE);
            assertThat(log.getQuantity()).isZero();
        }

        // 같은 orderId는 허브·상품이 같다는 전제라, order 버그나 잘못된 직접 호출일 때만 생긴다
        @Test
        void 차감한_재고와_복원_요청의_재고가_다르면_422이고_아무것도_쓰지_않는다() {
            givenTransactionRuns();
            givenLocked(seoul(100));
            givenNoLog(InventoryChangeType.RESTORE);
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.DECREASE))
                    .willReturn(Optional.of(log(busan(17), InventoryChangeType.DECREASE, 3)));

            assertThatThrownBy(() -> stockService.restoreStock(request(3), USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId", "productId");
                    });

            then(inventoryRepository).should(never()).increaseQuantity(any(), anyInt(), any(), any());
            then(inventoryLogRepository).should(never()).saveAndFlush(any());
        }

        @Test
        void 유니크_위반이면_먼저_처리된_복원_결과로_성공한다() {
            given(transactionTemplate.execute(any())).willThrow(new DataIntegrityViolationException("duplicate key"));
            given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, InventoryChangeType.RESTORE))
                    .willReturn(Optional.of(log(seoul(100), InventoryChangeType.RESTORE, 3)));

            StockRestoreResponse response = stockService.restoreStock(request(3), USER_ID);

            assertThat(response.restoredQuantity()).isEqualTo(3);
        }

        @Test
        void 유니크_위반인데_복원_로그가_없으면_원래_예외를_던진다() {
            DataIntegrityViolationException otherViolation = new DataIntegrityViolationException("other constraint");
            given(transactionTemplate.execute(any())).willThrow(otherViolation);
            givenNoLog(InventoryChangeType.RESTORE);

            assertThatThrownBy(() -> stockService.restoreStock(request(3), USER_ID))
                    .isSameAs(otherViolation);
        }
    }

    private void givenTransactionRuns() {
        given(transactionTemplate.execute(any())).willAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private void givenLocked(Inventory inventory) {
        given(inventoryRepository.findByHubIdAndProductIdForUpdate(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(inventory));
    }

    private void givenNoLog(InventoryChangeType changeType) {
        given(inventoryLogRepository.findByOrderIdAndChangeType(ORDER_ID, changeType)).willReturn(Optional.empty());
    }

    private void givenDecreased(int updatedRows) {
        given(inventoryRepository.decreaseQuantityIfEnough(eq(SEOUL_ID), eq(PRODUCT_ID), eq(3), any(LocalDateTime.class), eq(USER_ID)))
                .willReturn(updatedRows);
    }

    private InventoryLog savedLog() {
        ArgumentCaptor<InventoryLog> captor = ArgumentCaptor.forClass(InventoryLog.class);
        then(inventoryLogRepository).should().saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static StockRequest request(int quantity) {
        return new StockRequest(SEOUL_ID, PRODUCT_ID, quantity, ORDER_ID);
    }

    private static InventoryLog log(Inventory inventory, InventoryChangeType changeType, int quantity) {
        return changeType == InventoryChangeType.DECREASE
                ? InventoryLog.decrease(inventory, ORDER_ID, quantity)
                : InventoryLog.restore(inventory, ORDER_ID, quantity);
    }

    private static Inventory seoul(int quantity) {
        return inventory(INVENTORY_ID, SEOUL_ID, quantity);
    }

    private static Inventory busan(int quantity) {
        return inventory(BUSAN_INVENTORY_ID, BUSAN_ID, quantity);
    }

    private static Inventory inventory(UUID inventoryId, UUID hubId, int quantity) {
        Inventory inventory = Inventory.builder().hubId(hubId).productId(PRODUCT_ID).companyId(COMPANY_ID).quantity(quantity).build();
        ReflectionTestUtils.setField(inventory, "id", inventoryId);
        return inventory;
    }
}
