package com.yuksabal.hub.route.presentation;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.route.domain.HubRoute;
import com.yuksabal.hub.route.domain.HubRouteRepository;
import com.yuksabal.hub.route.application.HubRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HubRouteInternalController.class)
@Import({HubRouteService.class, GlobalExceptionHandler.class})
class HubRouteInternalControllerTest {

    private static final UUID SEOUL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BUSAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ROUTE_ID = UUID.fromString("00000000-0000-0000-0001-000000000004");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HubRouteRepository hubRouteRepository;

    // JpaAuditingConfig가 요구하는 JPA 메타모델 (WebMvcTest에는 JPA가 없다)
    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void 경로가_있으면_200과_경로를_반환한다() throws Exception {
        given(hubRouteRepository.findByDepartureHub_IdAndArrivalHub_Id(SEOUL_ID, BUSAN_ID))
                .willReturn(Optional.of(seoulToBusan()));

        mockMvc.perform(get("/internal/hub-routes").param("from", SEOUL_ID.toString()).param("to", BUSAN_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hubRouteId").value(ROUTE_ID.toString()))
                .andExpect(jsonPath("$.data.departureHubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.arrivalHubId").value(BUSAN_ID.toString()))
                .andExpect(jsonPath("$.data.distanceKm").value(407.98))
                .andExpect(jsonPath("$.data.durationMinutes").value(408));
    }

    @Test
    void 경로가_없으면_404를_반환한다() throws Exception {
        given(hubRouteRepository.findByDepartureHub_IdAndArrivalHub_Id(any(), any())).willReturn(Optional.empty());

        // 같은 허브끼리는 경로가 없다 (결정 #16)
        mockMvc.perform(get("/internal/hub-routes").param("from", SEOUL_ID.toString()).param("to", SEOUL_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("존재하지 않는 허브 경로입니다."));
    }

    @Test
    void 파라미터가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/internal/hub-routes").param("from", SEOUL_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("to"));
    }

    @Test
    void UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/internal/hub-routes").param("from", "abc").param("to", BUSAN_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("from"));
    }

    private HubRoute seoulToBusan() {
        Hub seoul = hub(SEOUL_ID, "서울특별시 센터");
        Hub busan = hub(BUSAN_ID, "부산광역시 센터");
        HubRoute route = HubRoute.builder()
                .departureHub(seoul)
                .arrivalHub(busan)
                .distanceKm(new BigDecimal("407.98"))
                .durationMinutes(408)
                .build();
        ReflectionTestUtils.setField(route, "id", ROUTE_ID);
        return route;
    }

    private Hub hub(UUID id, String name) {
        Hub hub = Hub.builder().hubName(name).address("주소").build();
        ReflectionTestUtils.setField(hub, "id", id);
        return hub;
    }
}
