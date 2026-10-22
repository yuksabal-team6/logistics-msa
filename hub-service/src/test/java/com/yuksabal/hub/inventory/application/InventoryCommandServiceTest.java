package com.yuksabal.hub.inventory.application;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.hub.domain.HubRepository;
import com.yuksabal.hub.inventory.presentation.dto.request.InventoryInboundRequest;
import com.yuksabal.hub.inventory.presentation.dto.request.InventoryQuantityUpdateRequest;
import com.yuksabal.hub.inventory.presentation.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.domain.Inventory;
import com.yuksabal.hub.inventory.domain.InventoryChangeType;
import com.yuksabal.hub.inventory.domain.InventoryLog;
import com.yuksabal.hub.inventory.domain.InventoryLogRepository;
import com.yuksabal.hub.inventory.domain.InventoryRepository;
import com.yuksabal.hub.inventory.domain.ProductInfo;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_CREATE_CONFLICT;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_QUANTITY_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository·Feign·TransactionTemplate은 가짜로 두고, 검증 순서와 예외 코드만 확인한다
// DB 제약(부분 유니크 인덱스, CHECK)과 조건부 UPDATE의 실제 동작은 여기서 확인하지 않는다
@ExtendWith(MockitoExtension.class)
class InventoryCommandServiceTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryLogRepository inventoryLogRepository;
    @Mock
    private HubRepository hubRepository;
    @Mock
    private ProductReader productReader;
    @Mock
    private CompanyReader companyReader;
    @Mock
    private TransactionTemplate transactionTemplate;

    private InventoryCommandService inventoryCommandService;

    // 권한 정책은 가짜로 두지 않고 실제 객체를 쓴다. 서비스가 정책을 제대로 부르는지까지 기존 권한 테스트로 확인한다
    @BeforeEach
    void setUp() {
        inventoryCommandService = new InventoryCommandService(inventoryRepository, inventoryLogRepository, hubRepository, productReader,
                new InventoryAccessPolicy(companyReader), transactionTemplate);
    }

    @Nested
    class 수량_조정 {

        @Test
        void MASTER가_아니면_403이고_재고를_바꾸지_않는다() {
            assertThatThrownBy(() -> inventoryCommandService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository, inventoryLogRepository);
        }

        @Test
        void 그_사이_수량이_바뀌었으면_409와_현재_수량이고_로그를_남기지_않는다() {
            given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                    .willReturn(0);
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 95)));

            assertThatThrownBy(() -> inventoryCommandService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(INVENTORY_QUANTITY_CONFLICT);
                        assertThat(e.getDetails()).extracting("reason").containsExactly("현재 재고는 95개입니다.");
                    });

            verifyNoInteractions(inventoryLogRepository);
        }

        @Test
        void 재고가_없으면_404이고_로그를_남기지_않는다() {
            given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                    .willReturn(0);
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryCommandService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));

            verifyNoInteractions(inventoryLogRepository);
        }

        @Test
        void 수량을_줄이면_줄인_만큼_ADJUST_DECREASE_로그를_남긴다() {
            given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                    .willReturn(1);
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 80)));

            InventoryResponse response = inventoryCommandService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.MASTER);

            assertThat(response.quantity()).isEqualTo(80);
            InventoryLog log = savedLog();
            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.ADJUST_DECREASE);
            assertThat(log.getQuantity()).isEqualTo(20);
            assertThat(log.getOrderId()).isNull();
        }

        @Test
        void 수량을_늘리면_늘린_만큼_ADJUST_INCREASE_로그를_남긴다() {
            given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(130), any(LocalDateTime.class), eq(USER_ID)))
                    .willReturn(1);
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 130)));

            inventoryCommandService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 130), USER_ID, UserRole.MASTER);

            InventoryLog log = savedLog();
            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.ADJUST_INCREASE);
            assertThat(log.getQuantity()).isEqualTo(30);
        }
    }

    // 검증 순서: 권한 → 허브(DB) → 상품(company #1) → 활성 → DB 작업(TransactionTemplate) (#156)
    @Nested
    class 입고 {

        @Test
        void MASTER가_아니면_403이고_허브와_company와_DB를_건드리지_않는다() {
            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(hubRepository, productReader, transactionTemplate, inventoryRepository, inventoryLogRepository);
        }

        @Test
        void 허브가_없으면_404이고_company를_부르지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(false);

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId");
                    });

            verifyNoInteractions(productReader, transactionTemplate);
        }

        @Test
        void 상품이_없으면_404이고_DB_작업을_하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(productReader.findProduct(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                    });

            verifyNoInteractions(transactionTemplate);
        }

        // 404 외의 company 오류를 어떻게 넘기는지는 CompanyFeignAdapterTest가 확인한다
        @Test
        void 상품_조회가_실패하면_그대로_전달하고_DB_작업을_하지_않는다() {
            FeignServerException serverError = serverError();
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(productReader.findProduct(PRODUCT_ID)).willThrow(serverError);

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isSameAs(serverError);

            verifyNoInteractions(transactionTemplate);
        }

        @Test
        void 비활성_상품이면_422이고_DB_작업을_하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(productReader.findProduct(PRODUCT_ID)).willReturn(Optional.of(product(SEOUL_ID, false)));

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                        assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                    });

            verifyNoInteractions(transactionTemplate);
        }

        @Test
        void 재고가_있으면_수량을_늘리고_INBOUND_로그를_남긴다() {
            givenActiveProduct(SEOUL_ID);
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 100)));
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 150)));

            InventoryResponse response = inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER);

            assertThat(response.quantity()).isEqualTo(150);
            then(inventoryRepository).should().increaseQuantity(eq(INVENTORY_ID), eq(50), any(LocalDateTime.class), eq(USER_ID));
            then(inventoryRepository).should(never()).saveAndFlush(any(Inventory.class));
            InventoryLog log = savedLog();
            assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.INBOUND);
            assertThat(log.getQuantity()).isEqualTo(50);
            assertThat(log.getOrderId()).isNull();
        }

        @Test
        void 재고가_없으면_상품_업체를_담아_새로_만들고_INBOUND_로그를_남긴다() {
            givenActiveProduct(SEOUL_ID);
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
            given(inventoryRepository.saveAndFlush(any(Inventory.class))).willAnswer(inv -> inv.getArgument(0));

            InventoryResponse response = inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER);

            assertThat(response.quantity()).isEqualTo(50);
            ArgumentCaptor<Inventory> created = ArgumentCaptor.forClass(Inventory.class);
            then(inventoryRepository).should().saveAndFlush(created.capture());
            assertThat(created.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
            assertThat(savedLog().getChangeType()).isEqualTo(InventoryChangeType.INBOUND);
        }

        // 재고 행 유무와 관계없이 DB 작업 전에 확인한다 (#175). 허브 변경 후 이전 허브에 남은 수량 0 재고로 입고되는 것도 막힌다
        @Test
        void 상품_업체의_관리_허브가_아니면_422이고_DB_작업을_하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(productReader.findProduct(PRODUCT_ID)).willReturn(Optional.of(product(BUSAN_ID, true)));

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId");
                    });

            verifyNoInteractions(transactionTemplate);
        }

        // 같은 허브·상품을 동시에 처음 입고해 다른 요청이 먼저 재고를 만든 경우
        @Test
        void 재고_생성이_유니크_인덱스에_걸리면_409이고_로그를_남기지_않는다() {
            givenActiveProduct(SEOUL_ID);
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
            given(inventoryRepository.saveAndFlush(any(Inventory.class)))
                    .willThrow(integrityViolation("uk_inventories_hub_id_product_id"));

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(INVENTORY_CREATE_CONFLICT));

            verifyNoInteractions(inventoryLogRepository);
        }

        @Test
        void 재고_생성이_다른_제약에_걸리면_409로_바꾸지_않고_원래_예외를_던진다() {
            DataIntegrityViolationException otherViolation = integrityViolation("ck_inventories_quantity_non_negative");
            givenActiveProduct(SEOUL_ID);
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
            given(inventoryRepository.saveAndFlush(any(Inventory.class))).willThrow(otherViolation);

            assertThatThrownBy(() -> inventoryCommandService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isSameAs(otherViolation);

            verifyNoInteractions(inventoryLogRepository);
        }
    }

    private void givenTransactionRuns() {
        given(transactionTemplate.execute(any())).willAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private void givenActiveProduct(UUID productHubId) {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(hub(SEOUL_ID));
        given(productReader.findProduct(PRODUCT_ID)).willReturn(Optional.of(product(productHubId, true)));
    }

    private InventoryLog savedLog() {
        ArgumentCaptor<InventoryLog> captor = ArgumentCaptor.forClass(InventoryLog.class);
        then(inventoryLogRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private static InventoryInboundRequest inboundRequest(int quantity) {
        return new InventoryInboundRequest(SEOUL_ID, PRODUCT_ID, quantity);
    }

    private static ProductInfo product(UUID productHubId, boolean active) {
        return new ProductInfo(PRODUCT_ID, COMPANY_ID, productHubId, active);
    }

    private static FeignServerException serverError() {
        return new FeignServerException(503, "SERVICE_UNAVAILABLE", "서비스를 사용할 수 없습니다.", "CompanyClient#getProduct");
    }

    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException("constraint violation",
                new ConstraintViolationException("constraint violation", new SQLException(), constraintName));
    }

    private static Hub hub(UUID hubId) {
        Hub hub = Hub.builder().hubName("허브").address("주소").build();
        ReflectionTestUtils.setField(hub, "id", hubId);
        return hub;
    }

    private static Inventory inventory(UUID inventoryId, UUID hubId, int quantity) {
        return inventory(inventoryId, hubId, quantity, LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    // createdAt은 JPA Auditing이 채우는 값이라 직접 넣는다
    private static Inventory inventory(UUID inventoryId, UUID hubId, int quantity, LocalDateTime createdAt) {
        Inventory inventory = Inventory.builder().hub(hub(hubId)).productId(PRODUCT_ID).companyId(COMPANY_ID).quantity(quantity).build();
        ReflectionTestUtils.setField(inventory, "id", inventoryId);
        ReflectionTestUtils.setField(inventory, "createdAt", createdAt);
        return inventory;
    }
}
