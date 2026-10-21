package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.CompanyFeignAdapter;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import com.yuksabal.hub.inventory.service.InventoryAccessPolicy;
import com.yuksabal.hub.inventory.service.InventoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 수량 > 0·삭제 제외 조건이 실제로 걸러내는지는 DB가 필요해 여기서는 분기와 응답 형식만 확인한다
@WebMvcTest(InventoryInternalController.class)
@Import({InventoryQueryService.class, InventoryAccessPolicy.class, CompanyFeignAdapter.class, GlobalExceptionHandler.class})
class InventoryInternalControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final String STOCK_URL = "/internal/inventories/stock";

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
    void 상품에_남은_재고가_있으면_true를_반환한다() throws Exception {
        given(inventoryRepository.existsByProductIdAndQuantityGreaterThan(PRODUCT_ID, 0)).willReturn(true);

        mockMvc.perform(get(STOCK_URL).param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hasStock").value(true));

        then(inventoryRepository).should(never()).existsByCompanyIdAndQuantityGreaterThan(any(), anyInt());
    }

    // 수량 0인 재고만 있거나 재고가 아예 없어도 404가 아니라 false
    @Test
    void 상품에_남은_재고가_없으면_false를_반환한다() throws Exception {
        given(inventoryRepository.existsByProductIdAndQuantityGreaterThan(PRODUCT_ID, 0)).willReturn(false);

        mockMvc.perform(get(STOCK_URL).param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasStock").value(false));
    }

    @Test
    void 업체에_남은_재고가_있으면_true를_반환한다() throws Exception {
        given(inventoryRepository.existsByCompanyIdAndQuantityGreaterThan(COMPANY_ID, 0)).willReturn(true);

        mockMvc.perform(get(STOCK_URL).param("companyId", COMPANY_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasStock").value(true));

        then(inventoryRepository).should(never()).existsByProductIdAndQuantityGreaterThan(any(), anyInt());
    }

    @Test
    void 업체에_남은_재고가_없으면_false를_반환한다() throws Exception {
        given(inventoryRepository.existsByCompanyIdAndQuantityGreaterThan(COMPANY_ID, 0)).willReturn(false);

        mockMvc.perform(get(STOCK_URL).param("companyId", COMPANY_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasStock").value(false));
    }

    @Test
    void 조회_조건이_없으면_400() throws Exception {
        mockMvc.perform(get(STOCK_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("singleTarget"));

        then(inventoryRepository).shouldHaveNoInteractions();
    }

    @Test
    void 상품과_업체를_함께_보내면_400() throws Exception {
        mockMvc.perform(get(STOCK_URL)
                        .param("productId", PRODUCT_ID.toString())
                        .param("companyId", COMPANY_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("singleTarget"));

        then(inventoryRepository).shouldHaveNoInteractions();
    }

    @Test
    void ID가_UUID_형식이_아니면_400() throws Exception {
        mockMvc.perform(get(STOCK_URL).param("productId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("productId"));
    }
}
