package com.yuksabal.hub.route.controller;

import com.yuksabal.common.exception.GlobalExceptionHandler;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.route.entity.HubRoute;
import com.yuksabal.hub.route.repository.HubRouteRepository;
import com.yuksabal.hub.route.service.HubRouteService;
import org.junit.jupiter.api.Test;
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

import java.math.BigDecimal;
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
@WebMvcTest(HubRouteController.class)
@Import({HubRouteService.class, GlobalExceptionHandler.class})
class HubRouteControllerTest {

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
    void 경로_목록을_PageResponse로_반환한다() throws Exception {
        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> new PageImpl<>(List.of(seoulToBusan()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/hub-routes")
                        .param("departureHubId", SEOUL_ID.toString())
                        .param("arrivalHubId", BUSAN_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].hubRouteId").value(ROUTE_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].departureHubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].arrivalHubId").value(BUSAN_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].distanceKm").value(407.98))
                .andExpect(jsonPath("$.data.items[0].durationMinutes").value(408))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 결과가_없으면_빈_목록을_반환한다() throws Exception {
        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        // 같은 허브끼리는 경로가 없다 (결정 #16)
        mockMvc.perform(get("/api/v1/hub-routes")
                        .param("departureHubId", SEOUL_ID.toString())
                        .param("arrivalHubId", SEOUL_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 정렬_기준_끝에_id를_붙인다() throws Exception {
        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/hub-routes").param("sort", "distanceKm,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(hubRouteRepository).should().findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("distanceKm"), Sort.Order.asc("id")));
    }

    @Test
    void 허용하지_않은_정렬_필드는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/hub-routes").param("sort", "deletedAt,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));

        then(hubRouteRepository).should(never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void UUID_형식이_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/hub-routes").param("departureHubId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("departureHubId"));
    }

    private HubRoute seoulToBusan() {
        HubRoute route = HubRoute.builder()
                .departureHub(hub(SEOUL_ID, "서울특별시 센터"))
                .arrivalHub(hub(BUSAN_ID, "부산광역시 센터"))
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


    @Test
    void 조건_없이_경로_목록을_조회한다() throws Exception {
        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation ->
                        new PageImpl<>(List.of(seoulToBusan()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/hub-routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].departureHubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].arrivalHubId").value(BUSAN_ID.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 출발_허브_ID만으로_경로_목록을_조회한다() throws Exception {
        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation ->
                        new PageImpl<>(List.of(seoulToBusan()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/hub-routes")
                        .param("departureHubId", SEOUL_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].departureHubId").value(SEOUL_ID.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 도착_허브_ID만으로_경로_목록을_조회한다() throws Exception {
        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation ->
                        new PageImpl<>(List.of(seoulToBusan()), invocation.getArgument(1), 1));

        mockMvc.perform(get("/api/v1/hub-routes")
                        .param("arrivalHubId", BUSAN_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].arrivalHubId").value(BUSAN_ID.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 존재하지_않는_허브_ID로_조회하면_빈_목록을_반환한다() throws Exception {
        UUID nonExistentHubId =
                UUID.fromString("00000000-0000-0000-0000-000000000099");

        given(hubRouteRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willAnswer(invocation ->
                        Page.empty(invocation.getArgument(1)));

        mockMvc.perform(get("/api/v1/hub-routes")
                        .param("departureHubId", nonExistentHubId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

}
