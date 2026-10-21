package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.dto.request.InventoryLogSearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventorySearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryStockRequest;
import com.yuksabal.hub.inventory.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository·Feign은 가짜로 두고, 검증 순서와 예외 코드만 확인한다
// 검색 조건(Specification)이 실제로 걸러내는지는 DB가 필요해 여기서 확인하지 않는다
@ExtendWith(MockitoExtension.class)
class InventoryQueryServiceTest {

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
    private CompanyReader companyReader;

    private InventoryQueryService inventoryQueryService;

    // 권한 정책은 가짜로 두지 않고 실제 객체를 쓴다. 서비스가 정책을 제대로 부르는지까지 기존 권한 테스트로 확인한다
    @BeforeEach
    void setUp() {
        inventoryQueryService = new InventoryQueryService(inventoryRepository, inventoryLogRepository, hubRepository,
                new InventoryAccessPolicy(companyReader));
    }

    @Nested
    class 재고_목록_조회 {

        @ParameterizedTest
        @EnumSource(value = UserRole.class, names = {"USER", "COMPANY", "DELIVERY"})
        void MASTER가_아니면_403이고_재고를_조회하지_않는다(UserRole role) {
            assertThatThrownBy(() -> inventoryQueryService.getInventories(new InventorySearchRequest(null, null), PageRequest.of(0, 10), role))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        void 허용하지_않은_정렬_필드면_400이고_재고를_조회하지_않는다() {
            PageRequest pageable = PageRequest.of(0, 10, Sort.by("productId"));

            assertThatThrownBy(() -> inventoryQueryService.getInventories(new InventorySearchRequest(null, null), pageable, UserRole.MASTER))
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
            assertThatThrownBy(() -> inventoryQueryService.getInventory(INVENTORY_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        void 재고가_없으면_404() {
            given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryQueryService.getInventory(INVENTORY_ID, UserRole.MASTER))
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
            assertThatThrownBy(() -> inventoryQueryService.getInventoriesByHub(SEOUL_ID, PageRequest.of(0, 10), UserRole.DELIVERY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(hubRepository, inventoryRepository);
        }

        @Test
        void 허브가_없으면_404이고_재고를_조회하지_않는다() {
            given(hubRepository.existsById(SEOUL_ID)).willReturn(false);

            assertThatThrownBy(() -> inventoryQueryService.getInventoriesByHub(SEOUL_ID, PageRequest.of(0, 10), UserRole.MASTER))
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
            assertThatThrownBy(() -> inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.USER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryRepository, companyReader);
        }

        @Test
        void MASTER는_업체를_확인하지_않는다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));

            InventoryResponse response = inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER);

            assertThat(response.quantity()).isEqualTo(10);
            verifyNoInteractions(companyReader);
        }

        @Test
        void 재고가_없으면_404이고_업체를_확인하지_않는다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());

            assertThatThrownBy(() -> inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("productId");
                    });

            verifyNoInteractions(companyReader);
        }

        @Test
        void COMPANY가_상품_업체의_관리자면_조회한다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyReader.findOwnerId(COMPANY_ID)).willReturn(Optional.of(USER_ID));

            InventoryResponse response = inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY);

            assertThat(response.inventoryId()).isEqualTo(INVENTORY_ID);
        }

        @Test
        void COMPANY가_다른_업체_관리자면_403() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyReader.findOwnerId(COMPANY_ID)).willReturn(Optional.of(OTHER_USER_ID));

            assertThatThrownBy(() -> inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        // 업체가 없거나 삭제되면 소유권을 확인할 수 없어 403
        @Test
        void COMPANY가_조회할_때_업체가_없으면_403() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyReader.findOwnerId(COMPANY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));
        }

        @Test
        void 업체_확인이_실패하면_그대로_전달한다() {
            FeignServerException serverError = serverError();
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(inventory(INVENTORY_ID, SEOUL_ID, 10)));
            given(companyReader.findOwnerId(COMPANY_ID)).willThrow(serverError);

            assertThatThrownBy(() -> inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.COMPANY))
                    .isSameAs(serverError);
        }

        // 관리 허브가 서울 → 부산으로 바뀐 뒤 서울에 수량 0인 재고가 이력으로 남은 경우 (#162)
        @Test
        void 재고가_여러_건이면_수량이_남은_재고를_반환한다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                    inventory(INVENTORY_ID, SEOUL_ID, 0, LocalDateTime.of(2026, 10, 1, 9, 0)),
                    inventory(BUSAN_INVENTORY_ID, BUSAN_ID, 20, LocalDateTime.of(2026, 10, 10, 9, 0))));

            InventoryResponse response = inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER);

            assertThat(response.hubId()).isEqualTo(BUSAN_ID);
            assertThat(response.quantity()).isEqualTo(20);
        }

        @Test
        void 재고가_여러_건이고_모두_0이면_가장_최근_재고를_반환한다() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                    inventory(BUSAN_INVENTORY_ID, BUSAN_ID, 0, LocalDateTime.of(2026, 10, 10, 9, 0)),
                    inventory(INVENTORY_ID, SEOUL_ID, 0, LocalDateTime.of(2026, 10, 1, 9, 0))));

            InventoryResponse response = inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER);

            assertThat(response.hubId()).isEqualTo(BUSAN_ID);
        }

        @Test
        void 수량이_남은_재고가_여러_허브에_있으면_409() {
            given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                    inventory(INVENTORY_ID, SEOUL_ID, 10, LocalDateTime.of(2026, 10, 1, 9, 0)),
                    inventory(BUSAN_INVENTORY_ID, BUSAN_ID, 20, LocalDateTime.of(2026, 10, 10, 9, 0))));

            assertThatThrownBy(() -> inventoryQueryService.getInventoryByProduct(PRODUCT_ID, USER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(INVENTORY_CONFLICT));
        }
    }

    @Nested
    class 재고_보유_여부 {

        @Test
        void 상품_기준이면_상품으로만_확인한다() {
            given(inventoryRepository.existsByProductIdAndQuantityGreaterThan(PRODUCT_ID, 0)).willReturn(true);

            assertThat(inventoryQueryService.hasStock(new InventoryStockRequest(PRODUCT_ID, null)).hasStock()).isTrue();

            then(inventoryRepository).should(never()).existsByCompanyIdAndQuantityGreaterThan(any(), anyInt());
        }

        @Test
        void 업체_기준이면_업체로만_확인한다() {
            given(inventoryRepository.existsByCompanyIdAndQuantityGreaterThan(COMPANY_ID, 0)).willReturn(false);

            assertThat(inventoryQueryService.hasStock(new InventoryStockRequest(null, COMPANY_ID)).hasStock()).isFalse();

            then(inventoryRepository).should(never()).existsByProductIdAndQuantityGreaterThan(any(), anyInt());
        }
    }

    @Nested
    class 재고_변경_이력_조회 {

        @Test
        void MASTER가_아니면_403이고_이력을_조회하지_않는다() {
            assertThatThrownBy(() -> inventoryQueryService.getInventoryLogs(logSearch(), PageRequest.of(0, 10), UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(inventoryLogRepository);
        }

        // 이력은 변경 순서로만 본다 (재고 목록에서 되는 quantity 정렬도 이력에서는 400)
        @Test
        void 허용하지_않은_정렬_필드면_400이고_이력을_조회하지_않는다() {
            PageRequest pageable = PageRequest.of(0, 10, Sort.by("quantity"));

            assertThatThrownBy(() -> inventoryQueryService.getInventoryLogs(logSearch(), pageable, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(VALIDATION_ERROR));

            then(inventoryLogRepository).should(never()).findAll(any(Specification.class), any(PageRequest.class));
        }
    }

    private static InventoryLogSearchRequest logSearch() {
        return new InventoryLogSearchRequest(null, null, null, null);
    }

    private static FeignServerException serverError() {
        return new FeignServerException(503, "SERVICE_UNAVAILABLE", "서비스를 사용할 수 없습니다.", "CompanyClient#getCompany");
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
