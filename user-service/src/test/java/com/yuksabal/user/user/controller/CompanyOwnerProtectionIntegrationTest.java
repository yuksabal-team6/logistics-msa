package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.HubClient;
import com.yuksabal.user.user.client.dto.CompanyResponse;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #113 업체 관리자 보호: 업체 owner_id가 회원을 가리키면 회원의 company_id도 그 업체를 가리켜야 한다
// 관리 업체 조회(company #11)는 테스트 대역. 현재 역할이 COMPANY인 회원만 조회한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyOwnerProtectionIntegrationTest {

    private static final String USERS_URL = "/api/v1/users/";
    private static final String OWNER_CHANGE_REQUIRED_MESSAGE = "업체 관리자를 먼저 변경해 주세요.";
    private static final UUID OWNED = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final String NEW_SLACK_ID = "U99999999";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @MockBean
    private HubClient hubClient;
    @MockBean
    private CompanyClient companyClient;
    // 비활성화 시 진행 중 배송 확인 (없음)
    @MockBean
    private DeliveryClient deliveryClient;

    private User master;
    private User owner;

    @BeforeEach
    void setUp() {
        given(deliveryClient.getInProgressDelivery(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                new DeliveryInProgressResponse(invocation.getArgument(0), false)));
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER, null);
        // 업체 생성 직후 상태: 업체의 관리자로 지정됐지만 아직 company_id는 비어 있다
        owner = save("owner01", UserRole.COMPANY, null);
    }

    // ---------- MASTER 회원 수정: companyId ----------

    @Test
    void 관리_업체로_소속을_지정하는_것은_허용한다() throws Exception {
        // given
        owns(owner, OWNED);

        // when & then
        updateUser(owner, Map.of("companyId", OWNED.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(OWNED.toString()));
        verify(companyClient).getCompaniesByOwner(owner.getId());
    }

    @Test
    void 관리_업체가_아닌_업체로_지정하면_422이고_바뀌지_않는다() throws Exception {
        // given
        owns(owner, OWNED);

        // when
        ResultActions result = updateUser(owner, Map.of("companyId", OTHER.toString(), "slackId", NEW_SLACK_ID));

        // then
        expectOwnerViolation(result, "companyId");
        // 같은 요청의 다른 필드도 반영되지 않는다
        User reloaded = reload(owner);
        assertThat(reloaded.getCompanyId()).isNull();
        assertThat(reloaded.getSlackId()).isEqualTo("Uowner01");
    }

    @Test
    void 관리_업체_소속인_관리자를_다른_업체로_옮기면_422() throws Exception {
        // given
        User ownerInCompany = save("owner02", UserRole.COMPANY, OWNED);
        owns(ownerInCompany, OWNED);

        // when
        ResultActions result = updateUser(ownerInCompany, Map.of("companyId", OTHER.toString()));

        // then
        expectOwnerViolation(result, "companyId");
        assertThat(reload(ownerInCompany).getCompanyId()).isEqualTo(OWNED);
    }

    @Test
    void 관리_업체가_없는_COMPANY_회원은_제한하지_않는다() throws Exception {
        // given
        owns(owner);

        // when & then
        updateUser(owner, Map.of("companyId", OTHER.toString())).andExpect(status().isOk());
        assertThat(reload(owner).getCompanyId()).isEqualTo(OTHER);
    }

    @Test
    void 관리_업체가_2개_이상이면_데이터_이상으로_422() throws Exception {
        // given
        owns(owner, OWNED, OTHER);

        // when
        ResultActions result = updateUser(owner, Map.of("companyId", OWNED.toString()));

        // then
        expectOwnerViolation(result, "companyId");
    }

    @Test
    void COMPANY가_아닌_회원은_관리_업체를_조회하지_않는다() throws Exception {
        // given
        User user = save("user01", UserRole.USER, null);

        // when & then
        updateUser(user, Map.of("companyId", OTHER.toString())).andExpect(status().isOk());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void companyId가_현재와_같으면_관리_업체를_조회하지_않는다() throws Exception {
        // given
        User ownerInCompany = save("owner02", UserRole.COMPANY, OWNED);

        // when & then
        updateUser(ownerInCompany, Map.of("companyId", OWNED.toString())).andExpect(status().isOk());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void companyId가_없으면_관리_업체를_조회하지_않는다() throws Exception {
        // given
        User ownerInCompany = save("owner02", UserRole.COMPANY, OWNED);

        // when & then
        updateUser(ownerInCompany, Map.of("slackId", NEW_SLACK_ID)).andExpect(status().isOk());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void 없는_업체면_존재_확인이_먼저_400() throws Exception {
        // given
        given(companyClient.getCompany(OTHER))
                .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "not found", List.of()));

        // when & then
        updateUser(owner, Map.of("companyId", OTHER.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("companyId"));
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void 관리_업체_조회가_타임아웃이면_502이고_바뀌지_않는다() throws Exception {
        // given
        given(companyClient.getCompaniesByOwner(owner.getId())).willThrow(timeout());

        // when & then
        updateUser(owner, Map.of("companyId", OWNED.toString()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));
        assertThat(reload(owner).getCompanyId()).isNull();
    }

    @Test
    void 관리_업체_조회_응답이_비어_있으면_확인하지_못한_것으로_502() throws Exception {
        // given
        given(companyClient.getCompaniesByOwner(owner.getId())).willReturn(ApiResponse.success(null));

        // when & then
        updateUser(owner, Map.of("companyId", OWNED.toString()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));
    }

    // ---------- 역할 변경 ----------

    @ParameterizedTest
    @ValueSource(strings = {"USER", "DELIVERY", "MASTER"})
    void 업체_관리자를_다른_역할로_바꾸면_422이고_바뀌지_않는다(String newRole) throws Exception {
        // given
        owns(owner, OWNED);

        // when
        ResultActions result = changeRole(owner, newRole);

        // then
        expectOwnerViolation(result, "role");
        assertThat(reload(owner).getRole()).isEqualTo(UserRole.COMPANY);
    }

    @Test
    void 관리_업체가_없는_COMPANY_회원은_역할을_바꿀_수_있다() throws Exception {
        // given
        owns(owner);

        // when & then
        changeRole(owner, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void COMPANY로_바꾸면_관리_업체를_조회하지_않는다() throws Exception {
        // given
        User user = save("user01", UserRole.USER, null);

        // when & then
        changeRole(user, "COMPANY").andExpect(status().isOk());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void 같은_역할로_바꾸면_관리_업체를_조회하지_않는다() throws Exception {
        // when & then
        changeRole(owner, "COMPANY").andExpect(status().isOk());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void 역할_변경_중_관리_업체_조회가_5xx면_502이고_바뀌지_않는다() throws Exception {
        // given
        given(companyClient.getCompaniesByOwner(owner.getId()))
                .willThrow(new FeignServerException(503, "SERVICE_UNAVAILABLE", "down", "CompanyClient#getCompaniesByOwner"));

        // when & then
        changeRole(owner, "USER")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));
        assertThat(reload(owner).getRole()).isEqualTo(UserRole.COMPANY);
    }

    // ---------- 그 밖의 동작 ----------

    @Test
    void 업체_관리자의_비활성화는_허용하고_조회하지_않는다() throws Exception {
        // given
        owns(owner, OWNED);

        // when & then
        changeStatus(owner, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void MASTER가_아니면_관리_업체를_조회하기_전에_403() throws Exception {
        // given
        User user = save("user01", UserRole.USER, null);

        // when & then
        mockMvc.perform(patch(USERS_URL + owner.getId() + "/role")
                        .header("X-User-Id", user.getId().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "USER"))))
                .andExpect(status().isForbidden());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    // ---------- helpers ----------

    // 관리 업체 조회(company #11)가 돌려줄 업체 목록. 인자가 없으면 관리 업체 없음
    private void owns(User user, UUID... companyIds) {
        List<CompanyResponse> companies = Arrays.stream(companyIds).map(CompanyResponse::new).toList();
        given(companyClient.getCompaniesByOwner(user.getId())).willReturn(ApiResponse.success(companies));
    }

    private void expectOwnerViolation(ResultActions result, String field) throws Exception {
        result.andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(OWNER_CHANGE_REQUIRED_MESSAGE))
                .andExpect(jsonPath("$.error.details[0].field").value(field));
    }

    private RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.GET, "http://company-service/internal/companies",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, (Long) null, request);
    }

    private User save(String username, UserRole role, UUID companyId) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .companyId(companyId)
                .build());
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MockHttpServletRequestBuilder asMaster(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", master.getId().toString())
                .header("X-User-Role", "MASTER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions updateUser(User target, Map<String, ?> changes) throws Exception {
        return mockMvc.perform(asMaster(patch(USERS_URL + target.getId()))
                .content(objectMapper.writeValueAsString(changes)));
    }

    private ResultActions changeRole(User target, String role) throws Exception {
        return mockMvc.perform(asMaster(patch(USERS_URL + target.getId() + "/role"))
                .content(objectMapper.writeValueAsString(Map.of("role", role))));
    }

    private ResultActions changeStatus(User target, boolean active) throws Exception {
        return mockMvc.perform(asMaster(patch(USERS_URL + target.getId() + "/status"))
                .content(objectMapper.writeValueAsString(Map.of("active", active))));
    }
}
