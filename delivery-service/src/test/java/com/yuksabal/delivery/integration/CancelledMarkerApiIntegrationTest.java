package com.yuksabal.delivery.integration;

import com.yuksabal.delivery.delivery.application.DeliveryService;
import com.yuksabal.delivery.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #302 시나리오 6: 취소 표식(CANCELLED + 허브·업체·주소·수령인 null)에 들어오는 API가 500(NPE)이 아니라 정해진 응답을 내는지
// MASTER로 요청한다: 표식은 주문자·담당자가 없어 MASTER만 조회·변경할 수 있다. 권한(403)보다 뒤의 상태 확인까지 가게 하기 위해서다
// 경로: Delivery·DeliveryService·DeliveryRouteLogService에서 표식에 닿는 상태 변경·담당자 지정·경로 기록·조회·삭제·재취소
class CancelledMarkerApiIntegrationTest extends IntegrationTestSupport {

    private static final UUID MASTER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Autowired
    private DeliveryService deliveryService;

    private UUID orderId;
    private UUID markerId;

    @BeforeEach
    void saveMarker() {
        orderId = UUID.randomUUID();
        deliveryService.cancelDelivery(orderId);   // 배송이 없는 취소 → 표식 저장
        markerId = jdbcTemplate.queryForObject(
                "SELECT delivery_id FROM delivery_service.p_deliveries WHERE order_id = ?", UUID.class, orderId);
    }

    @Test
    void DELIVERING_요청은_409_INVALID_STATUS_TRANSITION() throws Exception {
        asMaster(patchJson("/api/v1/deliveries/" + markerId + "/status", "{\"deliveryStatus\":\"DELIVERING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(statusOf(markerId)).isEqualTo("CANCELLED");
    }

    @Test
    void COMPLETED_요청은_409이고_주문_완료_알림을_보내지_않는다() throws Exception {
        asMaster(patchJson("/api/v1/deliveries/" + markerId + "/status", "{\"deliveryStatus\":\"COMPLETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        verifyNoInteractions(orderClient);
    }

    @Test
    void 담당자_지정은_409이고_user를_부르지_않는다() throws Exception {
        asMaster(patchJson("/api/v1/deliveries/" + markerId + "/manager",
                "{\"deliveryManagerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
        verifyNoInteractions(userClient);
    }

    // 표식은 경로 기록이 없다 → 경로 기록 API는 404 (배송 필드를 읽기 전에 경로 기록 조회에서 끝남)
    @Test
    void 경로_기록_상태_변경과_담당자_지정은_경로_기록이_없어_404() throws Exception {
        String routeUrl = "/api/v1/deliveries/" + markerId + "/routes/" + UUID.randomUUID();
        asMaster(patchJson(routeUrl + "/status", "{\"routeStatus\":\"IN_TRANSIT\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        asMaster(patchJson(routeUrl + "/manager", "{\"deliveryManagerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(userClient);
    }

    @Test
    void 경로_기록_목록은_빈_목록() throws Exception {
        asMaster(get("/api/v1/deliveries/" + markerId + "/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // 지금 동작 유지 (이슈 #302 "표식 행의 영향"): MASTER에게만 보이고, 응답의 null 필드는 그대로 null
    @Test
    void 상세_조회는_MASTER에게_200이고_없는_값은_null() throws Exception {
        asMaster(get("/api/v1/deliveries/" + markerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.departureHubId").value(nullValue()))
                .andExpect(jsonPath("$.data.address").value(nullValue()));
    }

    @Test
    void 상세_조회는_주문자가_없어_MASTER가_아니면_403() throws Exception {
        perform(get("/api/v1/deliveries/" + markerId), UUID.randomUUID(), "USER")
                .andExpect(status().isForbidden());
    }

    @Test
    void 목록은_MASTER에게만_보이고_허브_필터에서는_빠진다() throws Exception {
        asMaster(get("/api/v1/deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.deliveryId == '" + markerId + "')]").isNotEmpty());
        asMaster(get("/api/v1/deliveries").param("departureHubId", DEPARTURE_HUB_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.deliveryId == '" + markerId + "')]").isEmpty());
        perform(get("/api/v1/deliveries"), UUID.randomUUID(), "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.deliveryId == '" + markerId + "')]").isEmpty());
    }

    // 지금 동작 유지 (이슈 #302 한계): 끝난 배송이라 MASTER는 삭제할 수 있다. 삭제하면 같은 order_id의 자리가 빈다
    @Test
    void 삭제는_끝난_배송이라_200() throws Exception {
        asMaster(delete("/api/v1/deliveries/" + markerId))
                .andExpect(status().isOk());
        assertThat(rowsOf(orderId)).singleElement()
                .satisfies(row -> assertThat(row.get("deleted_at")).isNotNull());
    }

    // 내부 API로 다시 취소 → 이미 취소된 배송: 200, 행은 그대로 1개
    @Test
    void 다시_취소하면_200이고_표식은_그대로_1개() throws Exception {
        mockMvc.perform(post("/internal/deliveries/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andExpect(status().isOk());
        assertThat(rowsOf(orderId)).singleElement()
                .satisfies(row -> assertThat(row.get("delivery_status")).isEqualTo("CANCELLED"));
    }

    @Test
    void 진행_중_배송_확인에_잡히지_않는다() throws Exception {
        mockMvc.perform(get("/internal/deliveries/in-progress").param("deliveryManagerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasInProgressDelivery").value(false));
    }

    private ResultActions asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request, MASTER_ID, "MASTER");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, UUID userId, String role) throws Exception {
        return mockMvc.perform(request.header("X-User-Id", userId.toString()).header("X-User-Role", role));
    }

    private MockHttpServletRequestBuilder patchJson(String url, String body) {
        return patch(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
