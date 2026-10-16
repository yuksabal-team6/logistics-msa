package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import com.yuksabal.hub.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 검색 조건(Specification)이 실제로 걸러내는지는 DB가 필요해 여기서는 응답 형식·정렬 검증만 확인한다
@WebMvcTest(InventoryController.class)
@Import({InventoryService.class, GlobalExceptionHandler.class})
class InventoryControllerTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
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

    // JpaAuditingConfig가 요구하는 JPA 메타모델 (WebMvcTest에는 JPA가 없다)
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

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
    void 같은_상품_재고가_여러_건이면_409를_반환한다() throws Exception {
        given(inventoryRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(seoulInventory(), seoulInventory()));

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
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));

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

    private Inventory seoulInventory(int quantity) {
        Hub seoul = Hub.builder().hubName("서울특별시 센터").address("주소").build();
        ReflectionTestUtils.setField(seoul, "id", SEOUL_ID);
        Inventory inventory = Inventory.builder().hub(seoul).productId(PRODUCT_ID).companyId(COMPANY_ID).quantity(quantity).build();
        ReflectionTestUtils.setField(inventory, "id", INVENTORY_ID);
        return inventory;
    }
}
