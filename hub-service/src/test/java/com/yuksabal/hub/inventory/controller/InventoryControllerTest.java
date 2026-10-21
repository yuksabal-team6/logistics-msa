package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.CompanyFeignAdapter;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.client.dto.ProductResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import com.yuksabal.hub.inventory.service.InventoryAccessPolicy;
import com.yuksabal.hub.inventory.service.InventoryCommandService;
import com.yuksabal.hub.inventory.service.InventoryQueryService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 검색 조건(Specification)이 실제로 걸러내는지는 DB가 필요해 여기서는 응답 형식·정렬 검증만 확인한다
@WebMvcTest(InventoryController.class)
@Import({InventoryQueryService.class, InventoryCommandService.class, InventoryAccessPolicy.class, CompanyFeignAdapter.class, GlobalExceptionHandler.class})
class InventoryControllerTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID BUSAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID BUSAN_INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000004");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID LOG_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final String ROLE_HEADER = "X-User-Role";
    private static final String USER_ID_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryRepository inventoryRepository;

    @MockBean
    private InventoryLogRepository inventoryLogRepository;

    @MockBean
    private HubRepository hubRepository;

    @MockBean
    private CompanyClient companyClient;

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
    }

    @Test
    void 재고_목록을_PageResponse로_반환한다() throws Exception {
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(seoulInventory()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "MASTER")
                        .param("hubId", SEOUL_ID.toString())
                        .param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 조건_없이_재고_목록을_조회한다() throws Exception {
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(seoulInventory()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].inventoryId").value(INVENTORY_ID.toString()));
    }

    @Test
    void 결과가_없으면_빈_목록을_반환한다() throws Exception {
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "MASTER").param("hubId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 정렬_기준_끝에_id를_붙인다() throws Exception {
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "MASTER").param("sort", "quantity,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(inventoryRepository).should().findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("quantity"), Sort.Order.asc("id")));
    }

    @Test
    void 허용하지_않은_정렬_필드는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "MASTER").param("sort", "deletedAt,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));

        then(inventoryRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "MASTER").param("productId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "COMPANY", "DELIVERY"})
    void MASTER가_아니면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(inventoryRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void 정의되지_않은_역할이면_403을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories").header(ROLE_HEADER, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 역할_헤더가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(ROLE_HEADER));

        then(inventoryRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void 재고_ID로_상세_조회하면_200과_재고를_반환한다() throws Exception {
        given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(seoulInventory()));

        mockMvc.perform(get("/api/v1/inventories/{inventoryId}", INVENTORY_ID).header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("재고를 상세 조회했습니다."))
                .andExpect(jsonPath("$.data.inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(100));
    }

    @Test
    void 없는_재고_ID면_404를_반환한다() throws Exception {
        given(inventoryRepository.findById(any(UUID.class))).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/inventories/{inventoryId}", UUID.randomUUID()).header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 재고입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("inventoryId"));
    }

    // 권한이 없으면 재고가 있는지도 알 수 없도록 조회 전에 403
    @ParameterizedTest
    @ValueSource(strings = {"USER", "COMPANY", "DELIVERY"})
    void 상세_조회_권한이_없으면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/inventories/{inventoryId}", INVENTORY_ID).header(ROLE_HEADER, role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(inventoryRepository).should(never()).findById(any(UUID.class));
    }

    @Test
    void 재고_ID가_UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories/{inventoryId}", "abc").header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("inventoryId"));
    }

    @Test
    void 허브별_재고를_PageResponse로_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(seoulInventory()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", SEOUL_ID).header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("허브별 재고를 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 허브는_있지만_재고가_없으면_빈_목록을_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", SEOUL_ID).header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 없는_허브면_404를_반환한다() throws Exception {
        given(hubRepository.existsById(any(UUID.class))).willReturn(false);

        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", UUID.randomUUID()).header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 허브입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"));

        then(inventoryRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    // 권한이 없으면 허브가 있는지도 알 수 없도록 조회 전에 403
    @ParameterizedTest
    @ValueSource(strings = {"USER", "COMPANY", "DELIVERY"})
    void 허브별_재고_조회_권한이_없으면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", SEOUL_ID).header(ROLE_HEADER, role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(hubRepository).should(never()).existsById(any(UUID.class));
        then(inventoryRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void 허브별_재고_정렬_기준_끝에_id를_붙인다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(inventoryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", SEOUL_ID).header(ROLE_HEADER, "MASTER")
                        .param("sort", "quantity,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(inventoryRepository).should().findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("quantity"), Sort.Order.asc("id")));
    }

    @Test
    void 허브별_재고_허용하지_않은_정렬_필드는_400을_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);

        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", SEOUL_ID).header(ROLE_HEADER, "MASTER")
                        .param("sort", "deletedAt,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));
    }

    @Test
    void 허브_ID가_UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories/hubs/{hubId}", "abc").header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"));
    }

    @Test
    void MASTER는_업체_확인_없이_상품별_재고를_조회한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(seoulInventory()));

        mockMvc.perform(productRequest(PRODUCT_ID, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("상품별 재고를 조회했습니다."))
                .andExpect(jsonPath("$.data.inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(100));

        then(companyClient).should(never()).getCompany(any(UUID.class));
    }

    @Test
    void COMPANY는_상품_업체의_관리자면_상품별_재고를_조회한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(seoulInventory()));
        given(companyClient.getCompany(COMPANY_ID)).willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, USER_ID)));

        mockMvc.perform(productRequest(PRODUCT_ID, "COMPANY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryId").value(INVENTORY_ID.toString()));
    }

    @Test
    void COMPANY가_다른_업체_상품을_조회하면_403을_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(seoulInventory()));
        given(companyClient.getCompany(COMPANY_ID))
                .willReturn(ApiResponse.success(new CompanyResponse(COMPANY_ID, UUID.randomUUID())));

        mockMvc.perform(productRequest(PRODUCT_ID, "COMPANY"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void COMPANY가_조회할_때_업체가_없으면_403을_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(seoulInventory()));
        given(companyClient.getCompany(COMPANY_ID))
                .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of()));

        mockMvc.perform(productRequest(PRODUCT_ID, "COMPANY"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "DELIVERY"})
    void 상품별_재고_조회_권한이_없는_역할이면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(productRequest(PRODUCT_ID, role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(inventoryRepository).should(never()).findByProductId(any(UUID.class));
    }

    @Test
    void 상품_재고가_없으면_404를_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(any(UUID.class))).willReturn(List.of());

        mockMvc.perform(productRequest(UUID.randomUUID(), "MASTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 재고입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));
    }

    @Test
    void 상품_재고가_한_건이면_수량이_0이어도_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(seoulInventory(0)));

        mockMvc.perform(productRequest(PRODUCT_ID, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(0));
    }

    // 관리 허브가 서울 → 부산으로 바뀐 뒤: 서울에 수량 0인 재고가 이력으로 남고 부산에 새 재고가 생긴다
    @Test
    void 관리_허브가_바뀐_상품은_수량이_남은_재고를_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                inventoryAt(SEOUL_ID, INVENTORY_ID, 0, LocalDateTime.of(2026, 10, 1, 9, 0)),
                inventoryAt(BUSAN_ID, BUSAN_INVENTORY_ID, 20, LocalDateTime.of(2026, 10, 10, 9, 0))));

        mockMvc.perform(productRequest(PRODUCT_ID, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryId").value(BUSAN_INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.hubId").value(BUSAN_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(20));
    }

    // 조회 순서와 관계없이 생성 시각으로 고른다
    @Test
    void 관리_허브가_바뀐_상품의_재고가_모두_0이면_가장_최근_재고를_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                inventoryAt(BUSAN_ID, BUSAN_INVENTORY_ID, 0, LocalDateTime.of(2026, 10, 10, 9, 0)),
                inventoryAt(SEOUL_ID, INVENTORY_ID, 0, LocalDateTime.of(2026, 10, 1, 9, 0))));

        mockMvc.perform(productRequest(PRODUCT_ID, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hubId").value(BUSAN_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(0));
    }

    @Test
    void 수량이_남은_재고가_여러_허브에_있으면_409를_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(
                inventoryAt(SEOUL_ID, INVENTORY_ID, 10, LocalDateTime.of(2026, 10, 1, 9, 0)),
                inventoryAt(BUSAN_ID, BUSAN_INVENTORY_ID, 20, LocalDateTime.of(2026, 10, 10, 9, 0))));

        mockMvc.perform(productRequest(PRODUCT_ID, "MASTER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVENTORY_CONFLICT"));
    }

    @Test
    void 상품_ID가_UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories/products/{productId}", "abc")
                        .header(USER_ID_HEADER, USER_ID.toString())
                        .header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));
    }

    @Test
    void 사용자_ID_헤더가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories/products/{productId}", PRODUCT_ID).header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(USER_ID_HEADER));
    }

    @Test
    void 수량을_줄이면_조정하고_ADJUST_DECREASE_로그를_남긴다() throws Exception {
        given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                .willReturn(1);
        given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(seoulInventory(80)));

        mockMvc.perform(adjustRequest("MASTER", adjustBody(100, 80)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("재고 수량을 조정했습니다."))
                .andExpect(jsonPath("$.data.inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(80));

        InventoryLog log = savedLog();
        assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.ADJUST_DECREASE);
        assertThat(log.getQuantity()).isEqualTo(20);
        assertThat(log.getOrderId()).isNull();
    }

    @Test
    void 수량을_늘리면_ADJUST_INCREASE_로그를_남긴다() throws Exception {
        given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(120), any(LocalDateTime.class), eq(USER_ID)))
                .willReturn(1);
        given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(seoulInventory(120)));

        mockMvc.perform(adjustRequest("MASTER", adjustBody(100, 120)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(120));

        InventoryLog log = savedLog();
        assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.ADJUST_INCREASE);
        assertThat(log.getQuantity()).isEqualTo(20);
    }

    // 조회한 뒤 주문 차감 등으로 수량이 바뀌어 조건부 UPDATE가 0건인 경우
    @Test
    void 조회한_수량과_현재_수량이_다르면_409와_현재_수량을_반환한다() throws Exception {
        given(inventoryRepository.updateQuantityIfMatches(eq(INVENTORY_ID), eq(100), eq(80), any(LocalDateTime.class), eq(USER_ID)))
                .willReturn(0);
        given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(seoulInventory(95)));

        mockMvc.perform(adjustRequest("MASTER", adjustBody(100, 80)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVENTORY_QUANTITY_CONFLICT"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"))
                .andExpect(jsonPath("$.error.details[0].reason").value("현재 재고는 95개입니다."));

        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 조정할_재고가_없으면_404를_반환한다() throws Exception {
        given(inventoryRepository.updateQuantityIfMatches(any(UUID.class), anyInt(), anyInt(), any(LocalDateTime.class), any(UUID.class)))
                .willReturn(0);
        given(inventoryRepository.findById(any(UUID.class))).willReturn(Optional.empty());

        mockMvc.perform(adjustRequest("MASTER", adjustBody(100, 80)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 재고입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("inventoryId"));

        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 조정할_수량이_조회한_수량과_같으면_400을_반환한다() throws Exception {
        mockMvc.perform(adjustRequest("MASTER", adjustBody(100, 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantityChanged"))
                .andExpect(jsonPath("$.error.details[0].reason").value("조정할 재고 수량이 조회한 수량과 같습니다."));

        then(inventoryRepository).should(never())
                .updateQuantityIfMatches(any(UUID.class), anyInt(), anyInt(), any(LocalDateTime.class), any(UUID.class));
    }

    @Test
    void 조정할_수량이_음수면_400을_반환한다() throws Exception {
        mockMvc.perform(adjustRequest("MASTER", adjustBody(100, -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
    }

    @Test
    void 조회한_수량이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(adjustRequest("MASTER", """
                        {"quantity": 80}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("expectedQuantity"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "COMPANY", "DELIVERY"})
    void 수량_조정_권한이_없으면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(adjustRequest(role, adjustBody(100, 80)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(inventoryRepository).should(never())
                .updateQuantityIfMatches(any(UUID.class), anyInt(), anyInt(), any(LocalDateTime.class), any(UUID.class));
    }

    // updatedBy를 채울 수 없으므로 조정하지 않는다
    @Test
    void 수량_조정에서_사용자_ID_헤더가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(patch("/api/v1/inventories/{inventoryId}", INVENTORY_ID)
                        .header(ROLE_HEADER, "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(100, 80)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(USER_ID_HEADER));
    }

    @Test
    void 재고_변경_이력을_PageResponse로_반환한다() throws Exception {
        given(inventoryLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(decreaseLog()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/inventories/logs").header(ROLE_HEADER, "MASTER")
                        .param("hubId", SEOUL_ID.toString())
                        .param("changeType", "DECREASE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("재고 변경 이력을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].logId").value(LOG_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].changeType").value("DECREASE"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 정렬을_주지_않으면_최신순에_id를_붙여_조회한다() throws Exception {
        given(inventoryLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/inventories/logs").header(ROLE_HEADER, "MASTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(inventoryLogRepository).should().findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    }

    @Test
    void 이력에서_허용하지_않은_정렬_필드는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories/logs").header(ROLE_HEADER, "MASTER").param("sort", "quantity,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));

        then(inventoryLogRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void 없는_변경_유형이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/inventories/logs").header(ROLE_HEADER, "MASTER").param("changeType", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("changeType"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "COMPANY", "DELIVERY"})
    void 이력_조회_권한이_없으면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/inventories/logs").header(ROLE_HEADER, role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(inventoryLogRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void 재고가_있으면_상품_활성_확인_후_수량을_늘리고_INBOUND_로그를_남긴다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, SEOUL_ID, true)));
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(100)));
        given(inventoryRepository.findById(INVENTORY_ID)).willReturn(Optional.of(seoulInventory(150)));

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("재고를 입고했습니다."))
                .andExpect(jsonPath("$.data.inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(150));

        then(inventoryRepository).should().increaseQuantity(eq(INVENTORY_ID), eq(50), any(LocalDateTime.class), eq(USER_ID));
        then(inventoryRepository).should(never()).saveAndFlush(any(Inventory.class));
        InventoryLog log = savedLog();
        assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.INBOUND);
        assertThat(log.getQuantity()).isEqualTo(50);
        assertThat(log.getOrderId()).isNull();
    }

    // 업체가 비활성이면 상품도 비활성이라 (company #139) 비활성 업체의 상품도 여기서 막힌다
    @Test
    void 재고가_있어도_비활성_상품이면_422를_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, SEOUL_ID, false)));
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(100)));

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value("비활성 상품은 입고할 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        then(inventoryRepository).should(never()).increaseQuantity(any(UUID.class), anyInt(), any(LocalDateTime.class), any(UUID.class));
        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 재고가_없고_비활성_상품이면_422를_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, SEOUL_ID, false)));
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        then(inventoryRepository).should(never()).saveAndFlush(any(Inventory.class));
        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 재고가_없으면_상품_업체를_담아_새로_만들고_INBOUND_로그를_남긴다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, SEOUL_ID, true)));
        given(inventoryRepository.saveAndFlush(any(Inventory.class))).willAnswer(invocation -> {
            Inventory inventory = invocation.getArgument(0);
            ReflectionTestUtils.setField(inventory, "id", INVENTORY_ID);
            return inventory;
        });

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryId").value(INVENTORY_ID.toString()))
                .andExpect(jsonPath("$.data.hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.quantity").value(50));

        ArgumentCaptor<Inventory> created = ArgumentCaptor.forClass(Inventory.class);
        then(inventoryRepository).should().saveAndFlush(created.capture());
        assertThat(created.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
        InventoryLog log = savedLog();
        assertThat(log.getChangeType()).isEqualTo(InventoryChangeType.INBOUND);
        assertThat(log.getQuantity()).isEqualTo(50);
    }

    // 관리 허브를 바꾼 뒤 이전 허브에 남은 수량 0 재고로 입고하는 경우 (#175)
    @Test
    void 재고가_있어도_상품_업체의_관리_허브가_아니면_422를_반환한다() throws Exception {
        UUID busanId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.of(seoulInventory(0)));
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, busanId, true)));

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value("상품 업체의 관리 허브에만 입고할 수 있습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("상품 업체의 관리 허브는 " + busanId + "입니다."));

        then(inventoryRepository).should(never()).increaseQuantity(any(UUID.class), anyInt(), any(LocalDateTime.class), any(UUID.class));
        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 상품_업체의_관리_허브가_아니면_422를_반환한다() throws Exception {
        UUID busanId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, busanId, true)));

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("상품 업체의 관리 허브는 " + busanId + "입니다."));

        then(inventoryRepository).should(never()).saveAndFlush(any(Inventory.class));
        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 입고할_상품이_없으면_404를_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
        given(companyClient.getProduct(PRODUCT_ID))
                .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", List.of()));

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 상품입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));

        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @Test
    void 입고할_허브가_없으면_404를_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(false);

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 허브입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"));

        // 허브를 먼저 확인해, 없는 허브면 company를 호출하지 않는다
        then(companyClient).should(never()).getProduct(any(UUID.class));
        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    // 같은 허브·상품을 동시에 처음 입고해 다른 요청이 먼저 재고를 만든 경우
    @Test
    void 재고_생성이_유니크_인덱스에_걸리면_409를_반환한다() throws Exception {
        given(hubRepository.existsById(SEOUL_ID)).willReturn(true);
        given(hubRepository.getReferenceById(SEOUL_ID)).willReturn(seoulHub());
        given(inventoryRepository.findByHubIdAndProductId(SEOUL_ID, PRODUCT_ID)).willReturn(Optional.empty());
        given(companyClient.getProduct(PRODUCT_ID))
                .willReturn(ApiResponse.success(new ProductResponse(PRODUCT_ID, COMPANY_ID, SEOUL_ID, true)));
        given(inventoryRepository.saveAndFlush(any(Inventory.class))).willThrow(new DataIntegrityViolationException(
                "duplicate key",
                new ConstraintViolationException("duplicate key", new SQLException(), "uk_inventories_hub_id_product_id")));

        mockMvc.perform(inboundRequest("MASTER", inboundBody(50)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVENTORY_CREATE_CONFLICT"));

        then(inventoryLogRepository).should(never()).save(any(InventoryLog.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 입고_수량이_1_미만이면_400을_반환한다(int quantity) throws Exception {
        mockMvc.perform(inboundRequest("MASTER", inboundBody(quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));

        then(hubRepository).should(never()).existsById(any(UUID.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "COMPANY", "DELIVERY"})
    void 입고_권한이_없으면_403을_반환한다(String role) throws Exception {
        mockMvc.perform(inboundRequest(role, inboundBody(50)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        then(hubRepository).should(never()).existsById(any(UUID.class));
    }

    private MockHttpServletRequestBuilder inboundRequest(String role, String body) {
        return post("/api/v1/inventories/inbound")
                .header(USER_ID_HEADER, USER_ID.toString())
                .header(ROLE_HEADER, role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String inboundBody(int quantity) {
        return """
                {"hubId": "%s", "productId": "%s", "quantity": %d}
                """.formatted(SEOUL_ID, PRODUCT_ID, quantity);
    }

    private MockHttpServletRequestBuilder adjustRequest(String role, String body) {
        return patch("/api/v1/inventories/{inventoryId}", INVENTORY_ID)
                .header(USER_ID_HEADER, USER_ID.toString())
                .header(ROLE_HEADER, role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String adjustBody(int expectedQuantity, int quantity) {
        return """
                {"expectedQuantity": %d, "quantity": %d}
                """.formatted(expectedQuantity, quantity);
    }

    private InventoryLog savedLog() {
        ArgumentCaptor<InventoryLog> log = ArgumentCaptor.forClass(InventoryLog.class);
        then(inventoryLogRepository).should().save(log.capture());
        return log.getValue();
    }

    private MockHttpServletRequestBuilder productRequest(UUID productId, String role) {
        return get("/api/v1/inventories/products/{productId}", productId)
                .header(USER_ID_HEADER, USER_ID.toString())
                .header(ROLE_HEADER, role);
    }

    private Inventory seoulInventory() {
        return seoulInventory(100);
    }

    private InventoryLog decreaseLog() {
        InventoryLog log = InventoryLog.builder()
                .inventory(seoulInventory())
                .orderId(ORDER_ID)
                .changeType(InventoryChangeType.DECREASE)
                .quantity(3)
                .build();
        ReflectionTestUtils.setField(log, "id", LOG_ID);
        return log;
    }

    private Hub seoulHub() {
        Hub seoul = Hub.builder().hubName("서울특별시 센터").address("주소").build();
        ReflectionTestUtils.setField(seoul, "id", SEOUL_ID);
        return seoul;
    }

    private Inventory seoulInventory(int quantity) {
        Inventory inventory = Inventory.builder().hub(seoulHub()).productId(PRODUCT_ID).companyId(COMPANY_ID).quantity(quantity).build();
        ReflectionTestUtils.setField(inventory, "id", INVENTORY_ID);
        return inventory;
    }

    // 같은 상품의 재고가 여러 허브에 있는 경우용 (createdAt은 JPA Auditing이 채우는 값이라 직접 넣는다)
    private Inventory inventoryAt(UUID hubId, UUID inventoryId, int quantity, LocalDateTime createdAt) {
        Hub hub = Hub.builder().hubName("허브").address("주소").build();
        ReflectionTestUtils.setField(hub, "id", hubId);
        Inventory inventory = Inventory.builder().hub(hub).productId(PRODUCT_ID).companyId(COMPANY_ID).quantity(quantity).build();
        ReflectionTestUtils.setField(inventory, "id", inventoryId);
        ReflectionTestUtils.setField(inventory, "createdAt", createdAt);
        return inventory;
    }
}
