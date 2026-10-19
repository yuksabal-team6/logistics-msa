package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.client.dto.ProductResponse;
import com.yuksabal.hub.inventory.dto.request.InventoryInboundRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryLogSearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryQuantityUpdateRequest;
import com.yuksabal.hub.inventory.dto.request.InventorySearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryStockRequest;
import com.yuksabal.hub.inventory.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_CONFLICT;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_CREATE_CONFLICT;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_QUANTITY_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository·Feign·TransactionTemplate은 가짜로 두고, 검증 순서와 예외 코드만 확인한다
// DB 제약(부분 유니크 인덱스, CHECK)과 조건부 UPDATE의 실제 동작은 여기서 확인하지 않는다
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID BUSAN_INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000004");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryLogRepository inventoryLogRepository;
    @Mock
    private HubRepository hubRepository;
    @Mock
    private CompanyClient companyClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private InventoryService inventoryService;

    @Nested
    class 재고_목록_조회 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "COMPANY", "DELIVERY"})
        void MASTER가_아니면_403이고_재고를_조회하지_않는다(UserRole role) {
            assertThatThrownBy(() -> inventoryService.getInventories(new InventorySearchRequest(null, null), PageRequest.of(0, 10), role))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        void 허용하지_않은_정렬_필드면_400이고_재고를_조회하지_않는다() {
            PageRequest pageable = PageRequest.of(0, 10, Sort.by("productId"));

            assertThatThrownBy(() -> inventoryService.getInventories(new InventorySearchRequest(null, null), pageable, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("sort");
                    });

            verifyNoInteractions(inventoryRepository);
        }
    }

    @Nested
    class 재고_상세_조회 {

        @Test
        void MASTER가_아니면_403이고_재고를_조회하지_않는다() {
            assertThatThrownBy(() -> inventoryService.getInventory(INVENTORY_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        void 재고가_없으면_404() {
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.getInventory(INVENTORY_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("inventoryId");
                    });
        }
    }

    @Nested
    class 허브별_재고_조회 {

        @Test
        void MASTER가_아니면_403이고_허브를_확인하지_않는다() {
            assertThatThrownBy(() -> inventoryService.getInventoriesByHub(SEOUL_ID, PageRequest.of(0, 10), UserRole.DELIVERY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(hubRepository, inventoryRepository);
        }

        @Test
        void 허브가_없으면_404이고_재고를_조회하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(false);

            assertThatThrownBy(() -> inventoryService.getInventoriesByHub(SEOUL_ID, PageRequest.of(0, 10), UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId");
                    });

            verifyNoInteractions(inventoryRepository);
        }
    }

    @Nested
    class 상품별_재고_조회 {

        @Test
        void MASTER와_COMPANY가_아니면_403이고_재고와_업체를_확인하지_않는다() {
            assertThatThrownBy(() -> inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.USER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository, companyClient);
        }

        @Test
        void MASTER는_업체를_확인하지_않는다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));

            InventoryResponse response = inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER);

            assertThat(response.quantity()).isEqualTo(10);
            verifyNoInteractions(companyClient);
        }

        @Test
        void 재고가_없으면_404이고_업체를_확인하지_않는다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());

            assertThatThrownBy(() -> inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                    });

            verifyNoInteractions(companyClient);
        }

        @Test
        void COMPANY가_상품_업체의_관리자면_조회한다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyClient.getCompany(COMPANY_ID)).willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, USER_ID)));

            InventoryResponse response = inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY);

            assertThat(response.inventoryId()).isEqualTo(INVENTORY_ID);
        }

        @Test
        void COMPANY가_다른_업체_관리자면_403() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyClient.getCompany(COMPANY_ID)).willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, OTHER_USER_ID)));

            assertThatThrownBy(() -> inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        // 업체가 없거나 삭제되면 소유권을 확인할 수 없어 403
        @Test
        void COMPANY가_조회할_때_업체가_없으면_403() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyClient.getCompany(COMPANY_ID)).willThrow(notFound());

            assertThatThrownBy(() -> inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        @Test
        void 업체_확인이_404가_아닌_오류면_그대로_전달한다() {
            FeignServerException serverError = serverError();
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyClient.getCompany(COMPANY_ID)).willThrow(serverError);

            assertThatThrownBy(() -> inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isSameAs(serverError);
        }

        // 관리 허브가 서울 → 부산으로 바뀐 뒤 서울에 수량 0인 재고가 이력으로 남은 경우 (#162)
        @Test
        void 재고가_여러_건이면_수량이_남은_재고를_반환한다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                    inventory(INVENTORY_ID, SEOUL_ID, 0, LocalDateTime.of(2026, 10, 1, 9, 0)),
                    inventory(BUSAN_INVENTORY_ID, BUSAN_ID, 20, LocalDateTime.of(2026, 10, 10, 9, 0))));

            InventoryResponse response = inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER);

            assertThat(response.hubId()).isEqualTo(BUSAN_ID);
            assertThat(response.quantity()).isEqualTo(20);
        }

        @Test
        void 재고가_여러_건이고_모두_0이면_가장_최근_재고를_반환한다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                    inventory(BUSAN_INVENTORY_ID, BUSAN_ID, 0, LocalDateTime.of(2026, 10, 10, 9, 0)),
                    inventory(INVENTORY_ID, SEOUL_ID, 0, LocalDateTime.of(2026, 10, 1, 9, 0))));

            InventoryResponse response = inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER);

            assertThat(response.hubId()).isEqualTo(BUSAN_ID);
        }

        @Test
        void 수량이_남은_재고가_여러_허브에_있으면_409() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                    inventory(INVENTORY_ID, SEOUL_ID, 10, LocalDateTime.of(2026, 10, 1, 9, 0)),
                    inventory(BUSAN_INVENTORY_ID, BUSAN_ID, 20, LocalDateTime.of(2026, 10, 10, 9, 0))));

            assertThatThrownBy(() -> inventoryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(INVENTORY_CONFLICT));
        }
    }

    @Nested
    class 재고_보유_여부 {

        @Test
        void 상품_기준이면_상품으로만_확인한다() {
            given(inventoryRepository.existsByProductIdAndQuantityGreaterThan(PRODUCT_ID, 0)).willReturn(true);

            assertThat(inventoryService.hasStock(new InventoryStockRequest(PRODUCT_ID, null)).hasStock()).isTrue();

            then(inventoryRepository).should(never()).existsByCompanyIdAndQuantityGreaterThan(any(), anyInt());
        }

        @Test
        void 업체_기준이면_업체로만_확인한다() {
            given(inventoryRepository.existsByCompanyIdAndQuantityGreaterThan(COMPANY_ID, 0)).willReturn(false);

            assertThat(inventoryService.hasStock(new InventoryStockRequest(null, COMPANY_ID)).hasStock()).isFalse();

            then(inventoryRepository).should(never()).existsByProductIdAndQuantityGreaterThan(any(), anyInt());
        }
    }

    @Nested
    class 수량_조정 {

        @Test
        void MASTER가_아니면_403이고_재고를_바꾸지_않는다() {
            assertThatThrownBy(() -> inventoryService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository, inventoryLogRepository);
        }

        @Test
        void 조정할_수량이_조회한_수량과_같으면_400이고_재고를_바꾸지_않는다() {
            assertThatThrownBy(() -> inventoryService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 100), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR);
                        assertThat(e.getDetails()).extracting("field").containsExactly("quantity");
                    });

            verifyNoInteractions(inventoryRepository, inventoryLogRepository);
        }

        @Test
        void 그_사이_수량이_바뀌었으면_409와_현재_수량이고_로그를_남기지_않는다() {
            given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                    .willReturn(0);
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 95)));

            assertThatThrownBy(() -> inventoryService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.MASTER))
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

            assertThatThrownBy(() -> inventoryService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND));

            verifyNoInteractions(inventoryLogRepository);
        }

        @Test
        void 수량을_줄이면_줄인_만큼_ADJUST_DECREASE_로그를_남긴다() {
            given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                    .willReturn(1);
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(inventory(INVENTORY_ID, SEOUL_ID, 80)));

            InventoryResponse response = inventoryService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 80), USER_ID, UserRole.MASTER);

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

            inventoryService.adjustQuantity(INVENTORY_ID, new InventoryQuantityUpdateRequest(100, 130), USER_ID, UserRole.MASTER);

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
            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(hubRepository, companyClient, transactionTemplate, inventoryRepository, inventoryLogRepository);
        }

        @Test
        void 허브가_없으면_404이고_company를_부르지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(false);

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId");
                    });

            verifyNoInteractions(companyClient, transactionTemplate);
        }

        @Test
        void 상품이_없으면_404이고_DB_작업을_하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(companyClient.getProduct(PRODUCT_ID)).willThrow(notFound());

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                    });

            verifyNoInteractions(transactionTemplate);
        }

        @Test
        void company가_404가_아닌_4xx로_실패하면_그대로_전달하고_DB_작업을_하지_않는다() {
            FeignClientException badRequest = new FeignClientException(400, "VALIDATION_ERROR", "잘못된 요청입니다.", List.of());
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(companyClient.getProduct(PRODUCT_ID)).willThrow(badRequest);

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isSameAs(badRequest);

            verifyNoInteractions(transactionTemplate);
        }

        @Test
        void company가_5xx로_실패하면_그대로_전달하고_DB_작업을_하지_않는다() {
            FeignServerException serverError = serverError();
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(companyClient.getProduct(PRODUCT_ID)).willThrow(serverError);

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isSameAs(serverError);

            verifyNoInteractions(transactionTemplate);
        }

        @Test
        void 비활성_상품이면_422이고_DB_작업을_하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
            given(companyClient.getProduct(PRODUCT_ID)).willReturn(ApiResponse.success(product(SEOUL_ID, false)));

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
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

            InventoryResponse response = inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER);

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

            InventoryResponse response = inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER);

            assertThat(response.quantity()).isEqualTo(50);
            ArgumentCaptor<Inventory> created = ArgumentCaptor.forClass(Inventory.class);
            then(inventoryRepository).should().saveAndFlush(created.capture());
            assertThat(created.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
            assertThat(savedLog().getChangeType()).isEqualTo(InventoryChangeType.INBOUND);
        }

        @Test
        void 상품_업체의_관리_허브가_아니면_422이고_재고와_로그를_만들지_않는다() {
            givenActiveProduct(BUSAN_ID);
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                        assertThat(e.getDetails()).extracting("field").containsExactly("hubId");
                    });

            then(inventoryRepository).should(never()).saveAndFlush(any(Inventory.class));
            verifyNoInteractions(inventoryLogRepository);
        }

        // 같은 허브·상품을 동시에 처음 입고해 다른 요청이 먼저 재고를 만든 경우
        @Test
        void 재고_생성이_유니크_인덱스에_걸리면_409이고_로그를_남기지_않는다() {
            givenActiveProduct(SEOUL_ID);
            givenTransactionRuns();
            given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
            given(inventoryRepository.saveAndFlush(any(Inventory.class)))
                    .willThrow(integrityViolation("uk_inventories_hub_id_product_id"));

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
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

            assertThatThrownBy(() -> inventoryService.inbound(inboundRequest(50), USER_ID, UserRole.MASTER))
                    .isSameAs(otherViolation);

            verifyNoInteractions(inventoryLogRepository);
        }
    }

    @Nested
    class 재고_변경_이력_조회 {

        @Test
        void MASTER가_아니면_403이고_이력을_조회하지_않는다() {
            assertThatThrownBy(() -> inventoryService.getInventoryLogs(logSearch(), PageRequest.of(0, 10), UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryLogRepository);
        }

        // 이력은 변경 순서로만 본다 (재고 목록에서 되는 quantity 정렬도 이력에서는 400)
        @Test
        void 허용하지_않은_정렬_필드면_400이고_이력을_조회하지_않는다() {
            PageRequest pageable = PageRequest.of(0, 10, Sort.by("quantity"));

            assertThatThrownBy(() -> inventoryService.getInventoryLogs(logSearch(), pageable, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR));

            then(inventoryLogRepository).should(never()).findAll(any(Specification.class), any(PageRequest.class));
        }
    }

    private void givenTransactionRuns() {
        given(transactionTemplate.execute(any())).willAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private void givenActiveProduct(UUID productHubId) {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(hub(SEOUL_ID));
        given(companyClient.getProduct(PRODUCT_ID)).willReturn(ApiResponse.success(product(productHubId, true)));
    }

    private InventoryLog savedLog() {
        ArgumentCaptor<InventoryLog> captor = ArgumentCaptor.forClass(InventoryLog.class);
        then(inventoryLogRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private static InventoryInboundRequest inboundRequest(int quantity) {
        return new InventoryInboundRequest(SEOUL_ID, PRODUCT_ID, quantity);
    }

    private static InventoryLogSearchRequest logSearch() {
        return new InventoryLogSearchRequest(null, null, null, null);
    }

    private static ProductResponse product(UUID productHubId, boolean active) {
        return new ProductResponse(PRODUCT_ID, COMPANY_ID, productHubId, active);
    }

    private static FeignClientException notFound() {
        return new FeignClientException(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of());
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
