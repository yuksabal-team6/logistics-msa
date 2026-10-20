package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.HubClient;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
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

// GET·PATCH /api/v1/auth/me, PATCH /api/v1/auth/me/password
// 설계: JWT 검증은 게이트웨이가 하고, user-service는 게이트웨이가 넣어 준 X-User-Id로 회원을 찾는다.
// 테스트는 게이트웨이 역할을 대신한다 — 실제 로그인 토큰의 sub를 X-User-Id 헤더로 넣는다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthMeIntegrationTest {

    private static final String ME_URL = "/api/v1/auth/me";
    private static final String PASSWORD_URL = "/api/v1/auth/me/password";
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String PASSWORD = "Strong123!";
    private static final String NEW_PASSWORD = "Newpass123!";
    private static final String WRONG_PASSWORD = "Wrong123!";
    private static final String SLACK_ID = "U12345678";
    private static final String NEW_SLACK_ID = "U99999999";
    private static final String JWT_SECRET = TestJwt.randomBase64Secret(32);

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

    private record Session(UUID userId, String token) {
    }

    @BeforeEach
    void clean() {
        userRepository.deleteAllInBatch();
    }

    // ---------- GET /me ----------

    @Test
    void 로그인_토큰의_회원_정보를_조회하고_비밀번호는_없다() throws Exception {
        // given
        UUID hubId = UUID.randomUUID();
        register("user01", "user01@example.com", hubId);
        Session session = login("user01", PASSWORD);

        // when & then
        String responseBody = getMe(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("내 정보를 조회했습니다."))
                .andExpect(jsonPath("$.data.userId").value(session.userId().toString()))
                .andExpect(jsonPath("$.data.username").value("user01"))
                .andExpect(jsonPath("$.data.email").value("user01@example.com"))
                .andExpect(jsonPath("$.data.slackId").value(SLACK_ID))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.hubId").value(hubId.toString()))
                .andExpect(jsonPath("$.data.isPublic").value(true))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 평문 비밀번호도, BCrypt 해시($2...)도 응답에 없다
        assertThat(responseBody).doesNotContain(PASSWORD).doesNotContain("$2");
    }

    @Test
    void 토큰은_유효하지만_탈퇴한_회원이면_404() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");
        softDelete(session.userId());

        // when & then
        getMe(session)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 없는_회원_ID면_404() throws Exception {
        // when & then
        mockMvc.perform(get(ME_URL).header(USER_ID_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // 아래 두 경우는 게이트웨이를 거치면 생기지 않는다(토큰 오류는 게이트웨이가 401). common 핸들러 규칙대로 400

    @Test
    void X_User_Id_헤더가_없으면_400() throws Exception {
        // when & then
        mockMvc.perform(get(ME_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void X_User_Id_헤더가_UUID가_아니면_400() throws Exception {
        // when & then
        mockMvc.perform(get(ME_URL).header(USER_ID_HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // 설계상 JWT 검증은 게이트웨이 책임. user-service는 JWT를 보지 않고 X-User-Id 헤더의 회원으로 조회한다
    // (그래서 서비스 포트는 외부에 노출하지 않아야 한다 — Docker는 8081 미공개, 로컬은 local 프로필)

    @Test
    void Authorization_헤더_없이도_X_User_Id_헤더의_회원으로_조회된다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        mockMvc.perform(get(ME_URL).header(USER_ID_HEADER, session.userId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(session.userId().toString()));
    }

    @Test
    void 잘못된_토큰이_있어도_X_User_Id_헤더의_회원으로_조회된다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        mockMvc.perform(get(ME_URL).header(USER_ID_HEADER, session.userId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isOk());
    }

    // ---------- PATCH /me ----------

    @Test
    void 보낸_필드만_수정하고_updated_by는_토큰의_회원이다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("slackId", NEW_SLACK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("프로필이 수정되었습니다."))
                .andExpect(jsonPath("$.data.slackId").value(NEW_SLACK_ID))
                .andExpect(jsonPath("$.data.username").value("user01"))
                .andExpect(jsonPath("$.data.email").value("user01@example.com"))
                .andExpect(jsonPath("$.data.isPublic").value(true));

        User saved = findUser(session.userId());
        assertThat(saved.getSlackId()).isEqualTo(NEW_SLACK_ID);
        assertThat(saved.getUsername()).isEqualTo("user01");
        assertThat(saved.getUpdatedBy()).isEqualTo(session.userId());
    }

    @Test
    void 아이디_이메일_공개여부를_한번에_수정한다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("username", "user02", "email", "user02@example.com", "isPublic", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("user02"))
                .andExpect(jsonPath("$.data.email").value("user02@example.com"))
                .andExpect(jsonPath("$.data.isPublic").value(false));
    }

    @Test
    void 아이디를_바꾸면_새_아이디로만_로그인된다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when
        patchMe(session, Map.of("username", "user02", "email", "user02@example.com", "isPublic", false))
                .andExpect(status().isOk());

        // then
        assertThat(login("user02", PASSWORD).userId()).isEqualTo(session.userId());
        loginRequest("user01", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void 역할_소속_비밀번호는_요청에_있어도_바뀌지_않는다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");
        String passwordHash = findUser(session.userId()).getPassword();
        Map<String, Object> request = new HashMap<>();
        request.put("slackId", NEW_SLACK_ID);
        request.put("role", "MASTER");
        request.put("hubId", UUID.randomUUID().toString());
        request.put("companyId", UUID.randomUUID().toString());
        request.put("password", NEW_PASSWORD);

        // when
        patchMe(session, request).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"));

        // then
        User saved = findUser(session.userId());
        assertThat(saved.getRole().name()).isEqualTo("USER");
        assertThat(saved.getHubId()).isNull();
        assertThat(saved.getCompanyId()).isNull();
        assertThat(saved.getPassword()).isEqualTo(passwordHash);
    }

    @Test
    void 다른_회원의_아이디나_이메일로_바꾸면_409와_겹친_필드() throws Exception {
        // given
        registerAndLogin("taken01", "taken@example.com");
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("username", "taken01", "email", "taken@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "email")));

        assertThat(findUser(session.userId()).getUsername()).isEqualTo("user01");
    }

    @Test
    void 본인의_현재_아이디_이메일을_그대로_보내면_중복이_아니다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("username", "user01", "email", "user01@example.com")).andExpect(status().isOk());
    }

    @Test
    void 탈퇴한_회원의_아이디로는_바꿀_수_있다() throws Exception {
        // given
        Session deleted = registerAndLogin("old01", "old@example.com");
        softDelete(deleted.userId());
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("username", "old01")).andExpect(status().isOk());
    }

    @Test
    void 형식이_틀리거나_빈_값이면_400과_필드() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("username", "User!", "email", "not-an-email", "slackId", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "email", "slackId")));
    }

    // webhook 멘션(<@멤버ID>)에 쓰이므로 수정할 때도 멤버 ID 형식만 받는다 (#220)
    @Test
    void SlackID가_멤버_ID_형식이_아니면_400() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of("slackId", "it-user01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("slackId")));
    }

    @Test
    void 수정할_항목이_없으면_400() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        patchMe(session, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("수정할 항목이 없습니다."));
    }

    @Test
    void 탈퇴한_회원은_수정할_수_없다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");
        softDelete(session.userId());

        // when & then
        patchMe(session, Map.of("slackId", NEW_SLACK_ID)).andExpect(status().isNotFound());
    }

    // ---------- PATCH /me/password ----------

    @Test
    void 비밀번호를_바꾸면_200이고_응답에_비밀번호가_없다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        String responseBody = changePassword(session, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD);
    }

    @Test
    void 비밀번호를_바꾸면_BCrypt로_저장된다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when
        changePassword(session, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

        // then
        User saved = findUser(session.userId());
        assertThat(saved.getPassword()).startsWith("$2").isNotEqualTo(NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, saved.getPassword())).isTrue();
    }

    @Test
    void 비밀번호를_바꾸면_새_비밀번호로만_로그인된다() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when
        changePassword(session, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

        // then
        assertThat(login("user01", NEW_PASSWORD).userId()).isEqualTo(session.userId());
        loginRequest("user01", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void 현재_비밀번호가_틀리면_401_INVALID_PASSWORD() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        changePassword(session, WRONG_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"));

        // 기존 비밀번호가 그대로 유지된다
        assertThat(login("user01", PASSWORD).userId()).isEqualTo(session.userId());
    }

    @Test
    void 새_비밀번호가_정책에_맞지_않으면_400() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        changePassword(session, PASSWORD, "weak")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("newPassword")));
    }

    @Test
    void 새_비밀번호가_현재와_같으면_400() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        changePassword(session, PASSWORD, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("newPassword")));
    }

    @Test
    void 비밀번호_필수값이_없으면_400과_누락_필드() throws Exception {
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        mockMvc.perform(patch(PASSWORD_URL).header(USER_ID_HEADER, session.userId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(containsInAnyOrder("currentPassword", "newPassword")));
    }

    @Test
    void 현재_비밀번호가_틀리면_기존과_같은지_검사보다_401이_먼저다() throws Exception {
        // 순서: 형식 검증(DTO, 400) → 현재 비밀번호 확인(401) → 기존과 같은지(400)
        // given
        Session session = registerAndLogin("user01", "user01@example.com");

        // when & then
        changePassword(session, WRONG_PASSWORD, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"));
    }

    // ---------- helpers ----------

    private void register(String username, String email, UUID hubId) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of(
                "username", username, "password", PASSWORD, "slackId", SLACK_ID, "email", email));
        if (hubId != null) {
            request.put("hubId", hubId.toString());
        }
        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private Session registerAndLogin(String username, String email) throws Exception {
        register(username, email, null);
        return login(username, PASSWORD);
    }

    private ResultActions loginRequest(String username, String password) throws Exception {
        return mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private Session login(String username, String password) throws Exception {
        String responseBody = loginRequest(username, password)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(responseBody).path("data").path("accessToken").asText();
        UUID userId = UUID.fromString(TestJwt.parse(JWT_SECRET, token).getPayload().getSubject());
        return new Session(userId, token);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow();
    }

    private void softDelete(UUID userId) {
        User user = findUser(userId);
        user.delete(userId);
        userRepository.saveAndFlush(user);
    }

    // 게이트웨이가 검증된 JWT의 sub로 넣어 주는 헤더를 그대로 흉내 낸다
    private ResultActions getMe(Session session) throws Exception {
        return mockMvc.perform(get(ME_URL).header(USER_ID_HEADER, session.userId().toString()));
    }

    private ResultActions patchMe(Session session, Map<String, ?> request) throws Exception {
        return mockMvc.perform(patch(ME_URL).header(USER_ID_HEADER, session.userId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions changePassword(Session session, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(patch(PASSWORD_URL).header(USER_ID_HEADER, session.userId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("currentPassword", currentPassword, "newPassword", newPassword))));
    }
}
