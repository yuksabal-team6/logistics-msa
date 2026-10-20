package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.HubClient;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// [직접 호출 테스트] #67 PATCH /api/v1/users/{userId}, /role, /status (MASTER 전용)
// 게이트웨이가 넣어 주는 X-User-Id·X-User-Role을 직접 넣는다. 역할 변경 후 토큰 동작은 실제 로그인 토큰으로 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminIntegrationTest {

    private static final String USERS_URL = "/api/v1/users";
    private static final String ME_URL = "/api/v1/auth/me";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String PASSWORD = "Strong123!";
    private static final String WRONG_PASSWORD = "Wrong123!";
    private static final String ACCOUNT_DISABLED_MESSAGE = "비활성화된 계정입니다.";
    private static final String JWT_SECRET = TestJwt.randomBase64Secret(32);
    private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID COMPANY = UUID.fromString("20000000-0000-4000-8000-000000000001");
    // save()가 만드는 기본 slackId ("U" + username)
    private static final String ALICE_ORIGINAL_SLACK_ID = "Ualice01";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> JWT_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    // 허브·업체 내부 API는 테스트 대역 (기본 동작: 존재함). 검증 시나리오는 AffiliationValidationIntegrationTest
    @MockBean
    private HubClient hubClient;
    @MockBean
    private CompanyClient companyClient;
    // 비활성화·DELIVERY 역할 변경 시 진행 중 배송 확인 (기본 동작: 없음). 있을 때는 UserActiveWorkIntegrationTest
    @MockBean
    private DeliveryClient deliveryClient;

    private User master;
    private User master2;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        given(deliveryClient.getInProgressDelivery(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                new DeliveryInProgressResponse(invocation.getArgument(0), false)));
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER);
        master2 = save("master02", UserRole.MASTER);
        alice = save("alice01", UserRole.USER);
        bob = save("bob01", UserRole.USER);
    }

    // ---------- 공통 권한 ----------

    @Test
    void MASTER가_아니면_정보_수정은_403() throws Exception {
        for (User nonMaster : List.of(alice, bob)) {
            // when & then
            updateUser(nonMaster, alice.getId(), Map.of("slackId", "UX")).andExpect(status().isForbidden());
        }
    }

    @Test
    void MASTER가_아니면_역할_변경은_403() throws Exception {
        for (User nonMaster : List.of(alice, bob)) {
            // when & then
            changeRole(nonMaster, bob.getId(), "COMPANY").andExpect(status().isForbidden());
        }

        assertThat(reload(bob).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void MASTER가_아니면_상태_변경은_403() throws Exception {
        for (User nonMaster : List.of(alice, bob)) {
            // when & then
            changeStatus(nonMaster, bob.getId(), false).andExpect(status().isForbidden());
        }

        assertThat(reload(bob).isActive()).isTrue();
    }

    @Test
    void 역할_헤더만_MASTER로_위조하면_403() throws Exception {
        // when & then
        mockMvc.perform(patch(USERS_URL + "/" + bob.getId() + "/role")
                        .header(USER_ID_HEADER, alice.getId().toString())
                        .header(USER_ROLE_HEADER, "MASTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "MASTER"))))
                .andExpect(status().isForbidden());

        assertThat(reload(bob).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 비활성화된_MASTER는_관리_API를_쓸_수_없다() throws Exception {
        // given
        changeStatus(master, master2.getId(), false).andExpect(status().isOk());

        // when & then
        changeRole(master2, alice.getId(), "COMPANY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    }

    // ---------- PATCH /users/{id} ----------

    @Test
    void MASTER는_보낸_필드만_수정하고_소속도_바꿀_수_있다() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "username", "alice02", "slackId", "U99", "isPublic", false,
                "hubId", HUB.toString(), "companyId", COMPANY.toString());

        // when & then
        String responseBody = updateUser(master, alice.getId(), request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원 정보가 수정되었습니다."))
                .andExpect(jsonPath("$.data.username").value("alice02"))
                .andExpect(jsonPath("$.data.email").value("alice01@example.com"))
                .andExpect(jsonPath("$.data.slackId").value("U99"))
                .andExpect(jsonPath("$.data.isPublic").value(false))
                .andExpect(jsonPath("$.data.hubId").value(HUB.toString()))
                .andExpect(jsonPath("$.data.companyId").value(COMPANY.toString()))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // BCrypt 해시($2...)가 응답에 섞여 나가지 않는다
        assertThat(responseBody).doesNotContain("$2");
        assertThat(reload(alice).getUpdatedBy()).isEqualTo(master.getId());
    }

    @Test
    void 비밀번호_역할_상태는_정보_수정으로_바뀌지_않는다() throws Exception {
        // given
        String passwordHash = reload(alice).getPassword();
        Map<String, Object> request = new HashMap<>();
        request.put("slackId", "U99");
        request.put("password", "Hacked123!");
        request.put("role", "MASTER");
        request.put("active", false);

        // when
        updateUser(master, alice.getId(), request).andExpect(status().isOk());

        // then
        User saved = reload(alice);
        assertThat(saved.getPassword()).isEqualTo(passwordHash);
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void 다른_회원의_아이디나_이메일로_바꾸면_409() throws Exception {
        // when & then
        updateUser(master, alice.getId(), Map.of("username", "bob01", "email", "bob01@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "email")));
    }

    @Test
    void 형식이_틀린_필드가_있으면_400과_해당_필드() throws Exception {
        // when & then
        updateUser(master, alice.getId(), Map.of("username", "Bad!", "email", "nope", "slackId", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "email", "slackId")));
    }

    @Test
    void 소속_ID가_UUID_형식이_아니면_400() throws Exception {
        // when & then
        updateUser(master, alice.getId(), Map.of("hubId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 수정할_항목이_없으면_400() throws Exception {
        // when & then
        updateUser(master, alice.getId(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("수정할 항목이 없습니다."));
    }

    @Test
    void 소속_ID가_빈_문자열이면_변경_없음으로_무시하지_않고_400() throws Exception {
        for (String blank : List.of("", " ")) {
            for (String affiliationField : List.of("hubId", "companyId")) {
                // when & then
                updateUser(master, alice.getId(), Map.of("slackId", "U99", affiliationField, blank))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
            }
        }

        // 함께 보낸 slackId도 반영되지 않는다
        assertThat(reload(alice).getSlackId()).isEqualTo(ALICE_ORIGINAL_SLACK_ID);
    }

    @Test
    void 같은_키가_두_번_오면_400이고_아무것도_바꾸지_않는다() throws Exception {
        // given: 보고된 요청 그대로 (hubId 공백 + slackId 중복). 중복 키는 Map으로 만들 수 없어 JSON 문자열로 둔다
        String duplicateKeyJson = "{\"slackId\":\"U99999999\",\"isPublic\":false,\"hubId\":\" \",\"slackId\":\"U99\"}";

        // when & then
        patchRawJsonAsMaster(USERS_URL + "/" + alice.getId(), duplicateKeyJson)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        User reloaded = reload(alice);
        assertThat(reloaded.getSlackId()).isEqualTo(ALICE_ORIGINAL_SLACK_ID);
        assertThat(reloaded.isPublic()).isTrue();
    }

    @Test
    void 다른_오류_없이_같은_키만_두_번_와도_400() throws Exception {
        // given
        String duplicateKeyJson = "{\"slackId\":\"U99999999\",\"slackId\":\"U99\"}";

        // when & then
        patchRawJsonAsMaster(USERS_URL + "/" + alice.getId(), duplicateKeyJson)
                .andExpect(status().isBadRequest());

        assertThat(reload(alice).getSlackId()).isEqualTo(ALICE_ORIGINAL_SLACK_ID);
    }

    @Test
    void 없는_회원을_수정하면_404() throws Exception {
        // when & then
        updateUser(master, UUID.randomUUID(), Map.of("slackId", "U1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 탈퇴한_회원을_수정하면_404() throws Exception {
        // given
        softDelete(bob);

        // when & then
        updateUser(master, bob.getId(), Map.of("slackId", "U1")).andExpect(status().isNotFound());
    }

    // ---------- PATCH /users/{id}/role ----------

    @Test
    void MASTER는_역할을_바꾼다() throws Exception {
        // when & then
        changeRole(master, alice.getId(), "COMPANY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("회원 역할이 변경되었습니다."))
                .andExpect(jsonPath("$.data.role").value("COMPANY"));

        assertThat(reload(alice).getRole()).isEqualTo(UserRole.COMPANY);
    }

    @Test
    void 허용되지_않은_역할_값이면_400() throws Exception {
        for (String invalidRole : List.of("ADMIN", "master", "")) {
            // when & then
            changeRole(master, alice.getId(), invalidRole)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("role"));
        }
    }

    @Test
    void 역할_값이_없으면_400() throws Exception {
        // when & then
        patchRawJsonAsMaster(USERS_URL + "/" + alice.getId() + "/role", "{}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 같은_역할을_다시_요청하면_변경_없이_200() throws Exception {
        // when & then
        changeRole(master, alice.getId(), "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void 자기_자신의_역할은_바꿀_수_없다_422() throws Exception {
        // when & then
        changeRole(master, master.getId(), "USER")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));

        assertThat(reload(master).getRole()).isEqualTo(UserRole.MASTER);
    }

    @Test
    void 다른_MASTER가_있으면_MASTER를_강등할_수_있다() throws Exception {
        // when
        changeRole(master, master2.getId(), "USER").andExpect(status().isOk());

        // then
        assertThat(reload(master2).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 탈퇴한_회원의_역할을_바꾸면_404() throws Exception {
        // given
        softDelete(bob);

        // when & then
        changeRole(master, bob.getId(), "COMPANY").andExpect(status().isNotFound());
    }

    @Test
    void 역할을_바꾸면_예전_토큰은_403() throws Exception {
        // given
        String oldToken = login("alice01");
        changeRole(master, alice.getId(), "MASTER").andExpect(status().isOk());

        // when & then: 예전 토큰은 role=USER, DB는 MASTER → 불일치 403
        asGateway(get(USERS_URL), oldToken).andExpect(status().isForbidden());
    }

    @Test
    void 역할을_바꾼_뒤_다시_로그인하면_새_역할_토큰으로_통과() throws Exception {
        // given
        changeRole(master, alice.getId(), "MASTER").andExpect(status().isOk());

        // when
        String newToken = login("alice01");

        // then
        assertThat(TestJwt.parse(JWT_SECRET, newToken).getPayload().get("role")).isEqualTo("MASTER");
        asGateway(get(USERS_URL), newToken).andExpect(status().isOk());
    }

    // ---------- PATCH /users/{id}/status ----------

    @Test
    void MASTER는_회원을_비활성화한다() throws Exception {
        // when & then
        changeStatus(master, alice.getId(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계정 상태가 변경되었습니다."))
                .andExpect(jsonPath("$.data.active").value(false));

        // 비활성화는 탈퇴가 아니다
        assertThat(reload(alice).isActive()).isFalse();
        assertThat(reload(alice).getDeletedAt()).isNull();
    }

    @Test
    void MASTER는_비활성_회원을_다시_활성화한다() throws Exception {
        // given
        changeStatus(master, alice.getId(), false).andExpect(status().isOk());

        // when & then
        changeStatus(master, alice.getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        assertThat(reload(alice).isActive()).isTrue();
    }

    @Test
    void 같은_상태를_다시_요청하면_변경_없이_200() throws Exception {
        // when & then
        changeStatus(master, alice.getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void 상태값이_없으면_400() throws Exception {
        // when & then
        patchRawJsonAsMaster(USERS_URL + "/" + alice.getId() + "/status", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("active"));
    }

    @Test
    void 상태값이_boolean이_아니면_400() throws Exception {
        // when & then
        patchRawJsonAsMaster(USERS_URL + "/" + alice.getId() + "/status",
                objectMapper.writeValueAsString(Map.of("active", "maybe")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 자기_자신은_비활성화할_수_없다_422() throws Exception {
        // when & then
        changeStatus(master, master.getId(), false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));

        assertThat(reload(master).isActive()).isTrue();
    }

    @Test
    void 탈퇴한_회원의_상태를_바꾸면_404() throws Exception {
        // given
        softDelete(bob);

        // when & then
        changeStatus(master, bob.getId(), false).andExpect(status().isNotFound());
    }

    @Test
    void 비활성_계정에_맞는_비밀번호로_로그인하면_403() throws Exception {
        // given
        changeStatus(master, alice.getId(), false).andExpect(status().isOk());

        // when & then
        loginRequest("alice01", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void 비활성_계정에_틀린_비밀번호로_로그인하면_401() throws Exception {
        // given
        changeStatus(master, alice.getId(), false).andExpect(status().isOk());

        // when & then
        loginRequest("alice01", WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 비활성화_전에_받은_토큰으로_회원_조회하면_403() throws Exception {
        // given
        String tokenBeforeDisable = login("alice01");
        changeStatus(master, alice.getId(), false).andExpect(status().isOk());

        // when & then
        asGateway(get(USERS_URL + "/" + alice.getId()), tokenBeforeDisable)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.error.message").value(ACCOUNT_DISABLED_MESSAGE));
    }

    @Test
    void 비활성화_전에_받은_토큰으로_내_정보_조회하면_403() throws Exception {
        // given
        String tokenBeforeDisable = login("alice01");
        changeStatus(master, alice.getId(), false).andExpect(status().isOk());

        // when & then
        asGateway(get(ME_URL), tokenBeforeDisable)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.error.message").value(ACCOUNT_DISABLED_MESSAGE));
    }

    @Test
    void 다시_활성화하면_로그인된다() throws Exception {
        // given
        changeStatus(master, alice.getId(), false).andExpect(status().isOk());
        changeStatus(master, alice.getId(), true).andExpect(status().isOk());

        // when & then
        loginRequest("alice01", PASSWORD).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private User save(String username, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password(passwordEncoder.encode(PASSWORD))
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .build());
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private void softDelete(User user) {
        user.delete(master.getId());
        userRepository.saveAndFlush(user);
    }

    private MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder request, User user) {
        return request.header(USER_ID_HEADER, user.getId().toString())
                .header(USER_ROLE_HEADER, user.getRole().name())
                .contentType(MediaType.APPLICATION_JSON);
    }

    // PATCH /users/{userId}
    private ResultActions updateUser(User requester, UUID userId, Map<String, ?> request) throws Exception {
        return mockMvc.perform(withUser(patch(USERS_URL + "/" + userId), requester)
                .content(objectMapper.writeValueAsString(request)));
    }

    // PATCH /users/{userId}/role
    private ResultActions changeRole(User requester, UUID userId, String role) throws Exception {
        return mockMvc.perform(withUser(patch(USERS_URL + "/" + userId + "/role"), requester)
                .content(objectMapper.writeValueAsString(Map.of("role", role))));
    }

    // PATCH /users/{userId}/status
    private ResultActions changeStatus(User requester, UUID userId, boolean active) throws Exception {
        return mockMvc.perform(withUser(patch(USERS_URL + "/" + userId + "/status"), requester)
                .content(objectMapper.writeValueAsString(Map.of("active", active))));
    }

    // 정상 DTO로 만들 수 없는 본문(빈 객체, 중복 키 등)을 MASTER로 보낸다
    private ResultActions patchRawJsonAsMaster(String url, String rawJson) throws Exception {
        return mockMvc.perform(withUser(patch(url), master).content(rawJson));
    }

    private ResultActions loginRequest(String username, String password) throws Exception {
        return mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String login(String username) throws Exception {
        String responseBody = loginRequest(username, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).path("data").path("accessToken").asText();
    }

    // 게이트웨이 규칙대로 검증된 토큰의 sub·role을 X-User-Id·X-User-Role로 넣는다
    private ResultActions asGateway(MockHttpServletRequestBuilder request, String token) throws Exception {
        Claims claims = TestJwt.parse(JWT_SECRET, token).getPayload();
        return mockMvc.perform(request.header(USER_ID_HEADER, claims.getSubject())
                .header(USER_ROLE_HEADER, claims.get("role", String.class)));
    }
}
