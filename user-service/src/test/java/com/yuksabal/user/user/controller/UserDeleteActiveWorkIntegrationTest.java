package com.yuksabal.user.user.controller;

import com.jayway.jsonpath.JsonPath;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.OrderClient;
import com.yuksabal.user.user.client.dto.CompanyResponse;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.client.dto.OrderInProgressResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #68 회원 삭제 시 진행 중 업무 확인 (company 업체 관리자, order 진행 중 주문, delivery 진행 중 배송).
// 실제 확인 구현체 + 가짜 Feign 클라이언트. 확인 전에 먼저 비활성화하고, 막히면 되돌린다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserDeleteActiveWorkIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @MockBean
    private OrderClient orderClient;
    @MockBean
    private CompanyClient companyClient;
    @MockBean
    private DeliveryClient deliveryClient;

    private User master;
    private User alice;
    private User owner;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER);
        alice = save("alice01", UserRole.USER);
        owner = save("owner01", UserRole.COMPANY);
        given(orderClient.getInProgressOrder(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                new OrderInProgressResponse(invocation.getArgument(0), false)));
        given(companyClient.getCompaniesByOwner(any(UUID.class))).willReturn(ApiResponse.success(List.of()));
        given(deliveryClient.getInProgressDelivery(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                new DeliveryInProgressResponse(invocation.getArgument(0), false)));
    }

    // ---------- 통과 ----------

    @Test
    void 진행_중인_업무가_없으면_삭제된다() throws Exception {
        deleteByMaster(alice).andExpect(status().isOk());
        assertThat(userRepository.findById(alice.getId())).isEmpty();
        verify(orderClient).getInProgressOrder(alice.getId());
        verify(deliveryClient).getInProgressDelivery(alice.getId());
    }

    @Test
    void 확인하는_동안_대상은_이미_비활성이다() throws Exception {
        List<Boolean> activeDuringCheck = new ArrayList<>();
        given(deliveryClient.getInProgressDelivery(alice.getId())).willAnswer(invocation -> {
            activeDuringCheck.add(userRepository.findById(alice.getId()).orElseThrow().isActive());
            return ApiResponse.success(new DeliveryInProgressResponse(alice.getId(), false));
        });

        deleteByMaster(alice).andExpect(status().isOk());
        assertThat(activeDuringCheck).containsExactly(false);
    }

    // order는 주문 생성 시 /internal/users의 404·active=false를 보고 403으로 거절한다 (order 답변).
    // 삭제 처리 중에는 active=false, 삭제 뒤에는 404여야 새 주문이 생기지 않는다
    @Test
    void 삭제_처리_중_내부_조회는_비활성이고_삭제_뒤에는_404() throws Exception {
        List<String> internalDuringCheck = new ArrayList<>();
        given(orderClient.getInProgressOrder(alice.getId())).willAnswer(invocation -> {
            MockHttpServletResponse response = mockMvc.perform(get("/internal/users/" + alice.getId()))
                    .andReturn().getResponse();
            internalDuringCheck.add(response.getStatus() + " active="
                    + JsonPath.read(response.getContentAsString(), "$.data.active"));
            return ApiResponse.success(new OrderInProgressResponse(alice.getId(), false));
        });

        deleteByMaster(alice).andExpect(status().isOk());

        assertThat(internalDuringCheck).containsExactly("200 active=false");
        mockMvc.perform(get("/internal/users/" + alice.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void COMPANY가_아닌_회원은_관리_업체를_조회하지_않는다() throws Exception {
        deleteByMaster(alice).andExpect(status().isOk());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void 관리_업체가_없는_COMPANY_회원은_삭제된다() throws Exception {
        deleteByMaster(owner).andExpect(status().isOk());
        verify(companyClient).getCompaniesByOwner(owner.getId());
    }

    // ---------- 409 진행 중인 업무 ----------

    @Test
    void 진행_중인_주문이_있으면_409이고_삭제되지_않는다() throws Exception {
        given(orderClient.getInProgressOrder(alice.getId()))
                .willReturn(ApiResponse.success(new OrderInProgressResponse(alice.getId(), true)));

        deleteByMaster(alice)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("USER_HAS_ACTIVE_WORK"))
                .andExpect(jsonPath("$.error.message").value("진행 중인 업무가 있어 회원을 삭제할 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("order"))
                .andExpect(jsonPath("$.error.details[0].reason").value("진행 중인 주문이 있습니다."));
        // 확인 전에 비활성화했던 것을 되돌린다
        assertThat(userRepository.findById(alice.getId())).get().extracting(User::isActive).isEqualTo(true);
    }

    @Test
    void 진행_중인_배송이_있으면_409이고_삭제되지_않는다() throws Exception {
        given(deliveryClient.getInProgressDelivery(alice.getId()))
                .willReturn(ApiResponse.success(new DeliveryInProgressResponse(alice.getId(), true)));

        deleteByMaster(alice)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_HAS_ACTIVE_WORK"))
                .andExpect(jsonPath("$.error.details[0].field").value("delivery"))
                .andExpect(jsonPath("$.error.details[0].reason").value("진행 중인 배송이 있습니다."));
        assertThat(userRepository.findById(alice.getId())).get().extracting(User::isActive).isEqualTo(true);
    }

    @Test
    void 원래_비활성이던_회원은_막혀도_비활성으로_남는다() throws Exception {
        User inactive = userRepository.findById(alice.getId()).orElseThrow();
        inactive.changeActive(false);
        userRepository.saveAndFlush(inactive);
        given(orderClient.getInProgressOrder(alice.getId()))
                .willReturn(ApiResponse.success(new OrderInProgressResponse(alice.getId(), true)));

        deleteByMaster(alice).andExpect(status().isConflict());
        assertThat(userRepository.findById(alice.getId())).get().extracting(User::isActive).isEqualTo(false);
    }

    @Test
    void 업체_관리자는_409이고_삭제되지_않는다() throws Exception {
        UUID companyId = UUID.randomUUID();
        owns(owner, companyId);

        deleteByMaster(owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_HAS_ACTIVE_WORK"))
                .andExpect(jsonPath("$.error.details[0].field").value("company"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("업체(" + companyId + ")의 관리자입니다. 업체 관리자를 먼저 변경해 주세요."));
        assertThat(userRepository.findById(owner.getId())).isPresent();
    }

    @Test
    void 여러_서비스에_업무가_있으면_모두_알려준다() throws Exception {
        owns(owner, UUID.randomUUID());
        given(orderClient.getInProgressOrder(owner.getId()))
                .willReturn(ApiResponse.success(new OrderInProgressResponse(owner.getId(), true)));
        given(deliveryClient.getInProgressDelivery(owner.getId()))
                .willReturn(ApiResponse.success(new DeliveryInProgressResponse(owner.getId(), true)));

        deleteByMaster(owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("company", "order", "delivery")));
    }

    // ---------- 502 확인 실패 (fail-closed) ----------

    @Test
    void 주문_확인이_타임아웃이면_502이고_삭제되지_않는다() throws Exception {
        given(orderClient.getInProgressOrder(alice.getId())).willThrow(timeout());

        expectDependencyFailure(deleteByMaster(alice));
        assertThat(userRepository.findById(alice.getId())).get().extracting(User::isActive).isEqualTo(true);
    }

    @Test
    void 주문_확인이_5xx나_4xx면_502() throws Exception {
        given(orderClient.getInProgressOrder(alice.getId()))
                .willThrow(new FeignServerException(500, "INTERNAL_SERVER_ERROR", "서버 오류", "OrderClient#get"));
        expectDependencyFailure(deleteByMaster(alice));

        // 상대 서비스의 4xx(예: 경로 미배포 404)도 확인하지 못한 것이다. 상대 오류를 그대로 전달하지 않는다
        willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "없음", List.of()))
                .given(orderClient).getInProgressOrder(alice.getId());
        expectDependencyFailure(deleteByMaster(alice));
        assertThat(userRepository.findById(alice.getId())).isPresent();
    }

    @Test
    void 주문_확인_응답이_비어_있으면_502() throws Exception {
        given(orderClient.getInProgressOrder(alice.getId())).willReturn(ApiResponse.success(null));

        expectDependencyFailure(deleteByMaster(alice));
        assertThat(userRepository.findById(alice.getId())).isPresent();
    }

    @Test
    void 관리_업체_조회가_실패하거나_응답이_이상하면_502() throws Exception {
        given(companyClient.getCompaniesByOwner(owner.getId())).willThrow(timeout());
        expectDependencyFailure(deleteByMaster(owner));

        willReturn(ApiResponse.success(null)).given(companyClient).getCompaniesByOwner(owner.getId());
        expectDependencyFailure(deleteByMaster(owner));

        willReturn(ApiResponse.success(List.of(new CompanyResponse(null))))
                .given(companyClient).getCompaniesByOwner(owner.getId());
        expectDependencyFailure(deleteByMaster(owner));
        assertThat(userRepository.findById(owner.getId())).isPresent();
    }

    @Test
    void 배송_확인에_실패하거나_응답이_비어_있으면_502() throws Exception {
        given(deliveryClient.getInProgressDelivery(alice.getId())).willThrow(timeout());
        expectDependencyFailure(deleteByMaster(alice))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("timed out"));

        willReturn(ApiResponse.success(null)).given(deliveryClient).getInProgressDelivery(alice.getId());
        expectDependencyFailure(deleteByMaster(alice));
        assertThat(userRepository.findById(alice.getId())).get().extracting(User::isActive).isEqualTo(true);
    }

    // ---------- 순서 ----------

    @Test
    void 권한_대상_경계_조건은_업무_관계_확인보다_먼저() throws Exception {
        mockMvc.perform(delete("/api/v1/users/" + owner.getId())
                        .header("X-User-Id", alice.getId().toString()).header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/users/" + UUID.randomUUID())
                        .header("X-User-Id", master.getId().toString()).header("X-User-Role", "MASTER"))
                .andExpect(status().isNotFound());
        deleteByMaster(master).andExpect(status().isUnprocessableEntity());

        verify(orderClient, never()).getInProgressOrder(any());
        verify(companyClient, never()).getCompaniesByOwner(any());
        verify(deliveryClient, never()).getInProgressDelivery(any());
        // 막힌 요청은 대상 상태를 바꾸지 않는다
        assertThat(userRepository.findById(master.getId())).get().extracting(User::isActive).isEqualTo(true);
    }

    // ---------- helpers ----------

    private void owns(User user, UUID... companyIds) {
        List<CompanyResponse> companies = Arrays.stream(companyIds).map(CompanyResponse::new).toList();
        given(companyClient.getCompaniesByOwner(user.getId())).willReturn(ApiResponse.success(companies));
    }

    private ResultActions expectDependencyFailure(ResultActions result) throws Exception {
        return result.andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));
    }

    private RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.GET, "http://order-service/internal/orders/in-progress",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, (Long) null, request);
    }

    private ResultActions deleteByMaster(User target) throws Exception {
        return mockMvc.perform(delete("/api/v1/users/" + target.getId())
                .header("X-User-Id", master.getId().toString())
                .header("X-User-Role", "MASTER"));
    }

    private User save(String username, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .build());
    }
}
