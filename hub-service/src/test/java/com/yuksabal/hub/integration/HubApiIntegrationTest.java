package com.yuksabal.hub.integration;

import com.yuksabal.hub.support.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 허브·허브 경로 조회 API (외부 + 내부). data.sql 초기 데이터(허브 17개, 경로 272개)로 확인한다
// 로그인한 모든 역할이 조회할 수 있어 역할 헤더 없이 요청한다
class HubApiIntegrationTest extends IntegrationTestSupport {

    // data.sql 경로: 서울(01) → 부산(04)
    private static final UUID SEOUL_TO_BUSAN = UUID.fromString("00000000-0000-0000-0001-000000000004");

    @Nested
    class 허브 {

        @Test
        void 목록은_전체_17개() throws Exception {
            mockMvc.perform(get("/api/v1/hubs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(17));
        }

        @Test
        void 키워드로_이름을_검색한다() throws Exception {
            mockMvc.perform(get("/api/v1/hubs").param("keyword", "부산"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].hubId").value(OTHER_HUB_ID.toString()));
        }

        @Test
        void 상세_조회() throws Exception {
            mockMvc.perform(get("/api/v1/hubs/" + HUB_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hubName").value("서울특별시 센터"))
                    .andExpect(jsonPath("$.data.latitude").value(37.4779));
        }

        // 공개 상세와 내부 API가 같은 서비스 메서드를 써서 404 응답이 같다 (#284)
        @Test
        void 없는_허브면_404() throws Exception {
            mockMvc.perform(get("/api/v1/hubs/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.message").value("존재하지 않는 허브입니다."))
                    .andExpect(jsonPath("$.error.details[0].field").value("hubId"));
        }

        @Test
        void 내부_API는_허브_ID만_응답한다() throws Exception {
            mockMvc.perform(get("/internal/hubs/" + HUB_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hubId").value(HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.hubName").doesNotExist());
        }

        @Test
        void 내부_API도_없는_허브면_404() throws Exception {
            mockMvc.perform(get("/internal/hubs/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.message").value("존재하지 않는 허브입니다."))
                    .andExpect(jsonPath("$.error.details[0].field").value("hubId"));
        }
    }

    @Nested
    class 허브_경로 {

        @Test
        void 출발_허브로_조회하면_나머지_16개_허브로_가는_경로가_나온다() throws Exception {
            mockMvc.perform(get("/api/v1/hub-routes").param("departureHubId", HUB_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(16))
                    .andExpect(jsonPath("$.data.items[*].departureHubId", everyItem(is(HUB_ID.toString()))));
        }

        @Test
        void 같은_허브를_출발_도착으로_조회하면_빈_목록() throws Exception {
            mockMvc.perform(get("/api/v1/hub-routes")
                            .param("departureHubId", HUB_ID.toString())
                            .param("arrivalHubId", HUB_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        void 거리순으로_정렬한다() throws Exception {
            UUID nearestHubId = queryForObject(
                    "SELECT arrival_hub_id FROM hub_service.p_hub_routes WHERE departure_hub_id = ? ORDER BY distance_km LIMIT 1",
                    UUID.class, HUB_ID);

            mockMvc.perform(get("/api/v1/hub-routes")
                            .param("departureHubId", HUB_ID.toString())
                            .param("sort", "distanceKm,asc")
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].arrivalHubId").value(nearestHubId.toString()));
        }

        @Test
        void 허용하지_않은_정렬_필드면_400() throws Exception {
            mockMvc.perform(get("/api/v1/hub-routes").param("sort", "departureHub,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        }

        @Test
        void 상세_조회() throws Exception {
            mockMvc.perform(get("/api/v1/hub-routes/" + SEOUL_TO_BUSAN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.departureHubId").value(HUB_ID.toString()))
                    .andExpect(jsonPath("$.data.arrivalHubId").value(OTHER_HUB_ID.toString()));
        }

        @Test
        void 내부_API는_출발_도착으로_한_건을_조회한다() throws Exception {
            mockMvc.perform(get("/internal/hub-routes")
                            .param("from", HUB_ID.toString())
                            .param("to", OTHER_HUB_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hubRouteId").value(SEOUL_TO_BUSAN.toString()));
        }

        @Test
        void 내부_API는_경로가_없으면_404() throws Exception {
            mockMvc.perform(get("/internal/hub-routes")
                            .param("from", HUB_ID.toString())
                            .param("to", HUB_ID.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 내부_API는_도착_허브가_빠지면_400() throws Exception {
            mockMvc.perform(get("/internal/hub-routes").param("from", HUB_ID.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("to"));
        }
    }
}
