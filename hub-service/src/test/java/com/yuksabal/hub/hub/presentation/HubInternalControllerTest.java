package com.yuksabal.hub.hub.presentation;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.hub.domain.HubRepository;
import com.yuksabal.hub.hub.application.HubService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HubInternalController.class)
@Import({HubService.class, GlobalExceptionHandler.class})
class HubInternalControllerTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HubRepository hubRepository;

    // JpaAuditingConfig가 요구하는 JPA 메타모델 (WebMvcTest에는 JPA가 없다)
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void 허브가_있으면_200과_허브_ID를_반환한다() throws Exception {
        given(hubRepository.findById(SEOUL_ID)).willReturn(Optional.of(seoul()));

        mockMvc.perform(get("/internal/hubs/{hubId}", SEOUL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.hubName").doesNotExist());
    }

    @Test
    void 허브가_없으면_404를_반환한다() throws Exception {
        given(hubRepository.findById(any(UUID.class))).willReturn(Optional.empty());

        mockMvc.perform(get("/internal/hubs/{hubId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 허브입니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"));
    }

    @Test
    void 허브_ID가_UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/internal/hubs/{hubId}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("hubId"));
    }

    private Hub seoul() {
        Hub hub = Hub.builder().hubName("서울특별시 센터").address("서울특별시 송파구 송파대로 55").build();
        ReflectionTestUtils.setField(hub, "id", SEOUL_ID);
        return hub;
    }
}
