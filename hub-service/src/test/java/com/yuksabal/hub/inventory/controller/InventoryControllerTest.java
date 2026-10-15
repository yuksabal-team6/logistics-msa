package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.inventory.entity.Inventory;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 검색 조건(Specification)이 실제로 걸러내는지는 DB가 필요해 여기서는 응답 형식·정렬 검증만 확인한다
@WebMvcTest(InventoryController.class)
@Import({InventoryService.class, GlobalExceptionHandler.class})
class InventoryControllerTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID INVENTORY_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final String ROLE_HEADER = "X-User-Role";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryRepository inventoryRepository;

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

    private Inventory seoulInventory() {
        Hub seoul = Hub.builder().hubName("서울특별시 센터").address("주소").build();
        ReflectionTestUtils.setField(seoul, "id", SEOUL_ID);
        Inventory inventory = Inventory.builder().hub(seoul).productId(PRODUCT_ID).quantity(100).build();
        ReflectionTestUtils.setField(inventory, "id", INVENTORY_ID);
        return inventory;
    }
}
