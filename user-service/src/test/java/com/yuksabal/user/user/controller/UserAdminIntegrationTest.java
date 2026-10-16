package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.HubClient;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
    private static final String PASSWORD = "Strong123!";
    private static final String JWT_SECRET = TestJwt.randomBase64Secret(32);
    private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID COMPANY = UUID.fromString("20000000-0000-4000-8000-000000000001");

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

    private User master;
    private User master2;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER);
        master2 = save("master02", UserRole.MASTER);
        alice = save("alice01", UserRole.USER);
        bob = save("bob01", UserRole.USER);
    }

    // ---------- 공통 권한 ----------

    @Test
    void MASTER가_아니면_세_API_모두_403() throws Exception {
        for (User requester : new User[]{alice, bob}) {
            update(requester, alice.getId(), Map.of("slackId", "UX")).andExpect(status().isForbidden());
            role(requester, bob.getId(), "COMPANY").andExpect(status().isForbidden());
            active(requester, bob.getId(), false).andExpect(status().isForbidden());
        }
        assertThat(reload(bob).getRole()).isEqualTo(UserRole.USER);
        assertThat(reload(bob).isActive()).isTrue();
    }

    @Test
    void 역할_헤더만_MASTER로_위조하면_403() throws Exception {
        mockMvc.perform(patch(USERS_URL + "/" + bob.getId() + "/role")
                        .header("X-User-Id", alice.getId().toString()).header("X-User-Role", "MASTER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MASTER\"}"))
                .andExpect(status().isForbidden());
        assertThat(reload(bob).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 비활성화된_MASTER는_관리_API를_쓸_수_없다() throws Exception {
        active(master, master2.getId(), false).andExpect(status().isOk());

        role(master2, alice.getId(), "COMPANY")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    }

    // ---------- PATCH /users/{id} ----------

    @Test
    void MASTER는_보낸_필드만_수정하고_소속도_바꿀_수_있다() throws Exception {
        String body = update(master, alice.getId(), Map.of(
                "username", "alice02", "slackId", "U99", "isPublic", false,
                "hubId", HUB.toString(), "companyId", COMPANY.toString()))
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

        assertThat(body).doesNotContain("$2");
        assertThat(reload(alice).getUpdatedBy()).isEqualTo(master.getId());
    }

    @Test
    void 비밀번호_역할_상태는_정보_수정으로_바뀌지_않는다() throws Exception {
        String hash = reload(alice).getPassword();
        Map<String, Object> body = new HashMap<>();
        body.put("slackId", "U99");
        body.put("password", "Hacked123!");
        body.put("role", "MASTER");
        body.put("active", false);

        update(master, alice.getId(), body).andExpect(status().isOk());

        User saved = reload(alice);
        assertThat(saved.getPassword()).isEqualTo(hash);
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void 다른_회원의_아이디나_이메일로_바꾸면_409() throws Exception {
        update(master, alice.getId(), Map.of("username", "bob01", "email", "bob01@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "email")));
    }

    @Test
    void 형식이_틀리면_400_수정할_항목이_없어도_400() throws Exception {
        update(master, alice.getId(), Map.of("username", "Bad!", "email", "nope", "slackId", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "email", "slackId")));
        update(master, alice.getId(), Map.of("hubId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        update(master, alice.getId(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("수정할 항목이 없습니다."));
    }

    @Test
    void 소속_ID가_빈_문자열이면_변경_없음으로_무시하지_않고_400() throws Exception {
        for (String blank : new String[]{"", " "}) {
            for (String field : new String[]{"hubId", "companyId"}) {
                update(master, alice.getId(), Map.of("slackId", "U99", field, blank))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
            }
        }
        assertThat(reload(alice).getSlackId()).isEqualTo("Ualice01");
    }

    @Test
    void 같은_키가_두_번_오면_400이고_아무것도_바꾸지_않는다() throws Exception {
        // 보고된 요청 그대로: hubId 공백 + slackId 중복
        String json = "{\"slackId\":\"U99999999\",\"isPublic\":false,\"hubId\":\" \",\"slackId\":\"U99\"}";

        mockMvc.perform(withMaster(patch(USERS_URL + "/" + alice.getId())).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(withMaster(patch(USERS_URL + "/" + alice.getId()))
                        .content("{\"slackId\":\"U99999999\",\"slackId\":\"U99\"}"))
                .andExpect(status().isBadRequest());

        User reloaded = reload(alice);
        assertThat(reloaded.getSlackId()).isEqualTo("Ualice01");
        assertThat(reloaded.isPublic()).isTrue();
    }

    @Test
    void 없거나_탈퇴한_회원은_404() throws Exception {
        update(master, UUID.randomUUID(), Map.of("slackId", "U1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        bob.delete(master.getId());
        userRepository.saveAndFlush(bob);
        update(master, bob.getId(), Map.of("slackId", "U1")).andExpect(status().isNotFound());
        role(master, bob.getId(), "COMPANY").andExpect(status().isNotFound());
        active(master, bob.getId(), false).andExpect(status().isNotFound());
    }

    // ---------- PATCH /users/{id}/role ----------

    @Test
    void MASTER는_역할을_바꾼다() throws Exception {
        role(master, alice.getId(), "COMPANY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("회원 역할이 변경되었습니다."))
                .andExpect(jsonPath("$.data.role").value("COMPANY"));
        assertThat(reload(alice).getRole()).isEqualTo(UserRole.COMPANY);
    }

    @Test
    void 허용되지_않은_역할_값이면_400() throws Exception {
        for (String value : new String[]{"ADMIN", "master", ""}) {
            role(master, alice.getId(), value)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("role"));
        }
        mockMvc.perform(withMaster(patch(USERS_URL + "/" + alice.getId() + "/role")).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 같은_역할을_다시_요청하면_변경_없이_200() throws Exception {
        role(master, alice.getId(), "USER").andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void 자기_자신의_역할은_바꿀_수_없다_422() throws Exception {
        role(master, master.getId(), "USER")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(reload(master).getRole()).isEqualTo(UserRole.MASTER);
    }

    @Test
    void 다른_MASTER가_있으면_MASTER를_강등할_수_있다() throws Exception {
        role(master, master2.getId(), "USER").andExpect(status().isOk());
        assertThat(reload(master2).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 역할을_바꾸면_예전_토큰은_403_다시_로그인하면_새_역할로_통과() throws Exception {
        String oldToken = login("alice01");
        role(master, alice.getId(), "MASTER").andExpect(status().isOk());

        // 예전 토큰: role=USER, DB는 MASTER → 불일치 403
        asGateway(get(USERS_URL), oldToken).andExpect(status().isForbidden());

        String newToken = login("alice01");
        assertThat(TestJwt.parse(JWT_SECRET, newToken).getPayload().get("role")).isEqualTo("MASTER");
        asGateway(get(USERS_URL), newToken).andExpect(status().isOk());
    }

    // ---------- PATCH /users/{id}/status ----------

    @Test
    void MASTER는_비활성화하고_다시_활성화한다() throws Exception {
        active(master, alice.getId(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계정 상태가 변경되었습니다."))
                .andExpect(jsonPath("$.data.active").value(false));
        assertThat(reload(alice).isActive()).isFalse();
        assertThat(reload(alice).getDeletedAt()).isNull();

        active(master, alice.getId(), true).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
        assertThat(reload(alice).isActive()).isTrue();
    }

    @Test
    void 같은_상태를_다시_요청하면_변경_없이_200() throws Exception {
        active(master, alice.getId(), true).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    void 상태값이_없거나_형식이_틀리면_400() throws Exception {
        mockMvc.perform(withMaster(patch(USERS_URL + "/" + alice.getId() + "/status")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("active"));
        mockMvc.perform(withMaster(patch(USERS_URL + "/" + alice.getId() + "/status")).content("{\"active\":\"maybe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 자기_자신은_비활성화할_수_없다_422() throws Exception {
        active(master, master.getId(), false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(reload(master).isActive()).isTrue();
    }

    @Test
    void 비활성_계정은_비밀번호가_맞으면_403_틀리면_401() throws Exception {
        active(master, alice.getId(), false).andExpect(status().isOk());

        loginRequest("alice01", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        loginRequest("alice01", "Wrong123!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 비활성화_전에_받은_토큰으로도_user_service_API는_403() throws Exception {
        String token = login("alice01");
        active(master, alice.getId(), false).andExpect(status().isOk());

        asGateway(get(USERS_URL + "/" + alice.getId()), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        asGateway(get("/api/v1/auth/me"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void 다시_활성화하면_로그인된다() throws Exception {
        active(master, alice.getId(), false).andExpect(status().isOk());
        active(master, alice.getId(), true).andExpect(status().isOk());

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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withUser(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, User user) {
        return request.header("X-User-Id", user.getId().toString())
                .header("X-User-Role", user.getRole().name())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withMaster(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return withUser(request, master);
    }

    private ResultActions update(User requester, UUID userId, Map<String, ?> body) throws Exception {
        return mockMvc.perform(withUser(patch(USERS_URL + "/" + userId), requester)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions role(User requester, UUID userId, String role) throws Exception {
        return mockMvc.perform(withUser(patch(USERS_URL + "/" + userId + "/role"), requester)
                .content(objectMapper.writeValueAsString(Map.of("role", role))));
    }

    private ResultActions active(User requester, UUID userId, boolean active) throws Exception {
        return mockMvc.perform(withUser(patch(USERS_URL + "/" + userId + "/status"), requester)
                .content(objectMapper.writeValueAsString(Map.of("active", active))));
    }

    private ResultActions loginRequest(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String login(String username) throws Exception {
        String body = loginRequest(username, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asText();
    }

    // 게이트웨이 규칙대로 검증된 토큰의 sub·role을 X-User-Id·X-User-Role로 넣는다
    private ResultActions asGateway(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                    String token) throws Exception {
        Claims claims = TestJwt.parse(JWT_SECRET, token).getPayload();
        return mockMvc.perform(request.header("X-User-Id", claims.getSubject())
                .header("X-User-Role", claims.get("role", String.class)));
    }
}
