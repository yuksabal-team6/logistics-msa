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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 컨트롤러 → 서비스 → JPA(H2) → common 예외 처리까지 한 번에 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String USERNAME = "user01";
    private static final String EMAIL = "user01@example.com";
    private static final String PASSWORD = "Strong123!";
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

    @BeforeEach
    void clean() {
        userRepository.deleteAllInBatch();
    }

    // ---------- 회원가입 ----------

    @Test
    void 정상_회원가입은_201과_공통_응답_형식() throws Exception {
        // given
        UUID hubId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        Map<String, Object> request = Map.of("username", USERNAME, "password", PASSWORD, "slackId", "U12345678",
                "email", EMAIL, "hubId", hubId.toString(), "companyId", companyId.toString());

        // when
        String responseJson = register(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // then
        User saved = userRepository.findById(UUID.fromString(userIdOf(responseJson))).orElseThrow();
        assertThat(saved.getHubId()).isEqualTo(hubId);
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.isPublic()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isNull();
    }

    @Test
    void 비밀번호는_BCrypt_해시로_저장되고_응답에_없다() throws Exception {
        // when
        String responseJson = register(validBody(USERNAME, EMAIL))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // then
        assertThat(responseJson).doesNotContain(PASSWORD);
        User saved = userRepository.findAll().get(0);
        assertThat(saved.getPassword()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPassword())).isTrue();
    }

    @Test
    void 요청에_role을_넣어도_USER로_가입된다() throws Exception {
        // given
        Map<String, Object> request = validBodyWith("role", "MASTER");

        // when & then
        register(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("USER"));
        assertThat(userRepository.findAll().get(0).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 이메일이_중복이면_409() throws Exception {
        // given
        register(validBody("user01", "same@example.com")).andExpect(status().isCreated());

        // when & then
        register(validBody("user02", "same@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("email")));
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 아이디가_중복이면_409() throws Exception {
        // given
        register(validBody(USERNAME, "a@example.com")).andExpect(status().isCreated());

        // when & then
        register(validBody(USERNAME, "b@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username")));
    }

    @Test
    void 삭제된_회원의_아이디와_이메일로는_다시_가입할_수_있다() throws Exception {
        // given
        register(validBody(USERNAME, EMAIL)).andExpect(status().isCreated());
        softDelete(userRepository.findAll().get(0));

        // when & then
        register(validBody(USERNAME, EMAIL)).andExpect(status().isCreated());
    }

    @Test
    void 필수값이_없으면_400과_누락_필드_전부() throws Exception {
        // when & then
        register(Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(containsInAnyOrder("username", "password", "slackId", "email")));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 아이디_비밀번호_형식이_틀리면_400() throws Exception {
        // given
        Map<String, Object> request = Map.of("username", "User!", "password", "weak", "slackId", "U12345678",
                "email", "a@example.com");

        // when & then
        register(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "password")));
    }

    @Test
    void hubId가_UUID_형식이_아니면_400() throws Exception {
        // given
        Map<String, Object> request = validBodyWith("hubId", "not-a-uuid");

        // when & then
        register(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void hubId가_빈_문자열이면_소속_없이_가입되지_않고_400(String blank) throws Exception {
        // given
        Map<String, Object> request = validBodyWith("hubId", blank);

        // when & then
        register(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(userRepository.findByUsername(USERNAME)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void companyId가_빈_문자열이면_소속_없이_가입되지_않고_400(String blank) throws Exception {
        // given
        Map<String, Object> request = validBodyWith("companyId", blank);

        // when & then
        register(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(userRepository.findByUsername(USERNAME)).isEmpty();
    }

    @Test
    void 같은_키가_두_번_오면_400() throws Exception {
        // given
        // Map으로는 같은 키를 두 번 넣을 수 없어서 JSON을 직접 쓴다
        String duplicateKeyJson = "{\"username\":\"user01\",\"password\":\"" + PASSWORD + "\",\"slackId\":\"U1\","
                + "\"email\":\"user01@example.com\",\"username\":\"user02\"}";

        // when & then
        mockMvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content(duplicateKeyJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 오류_응답에_비밀번호가_노출되지_않는다() throws Exception {
        // given
        String rawPassword = "SecretPw1!";
        Map<String, Object> invalidUsernameRequest = Map.of("username", "ab", "password", rawPassword,
                "slackId", "U12345678", "email", "a@example.com");

        // when & then
        register(invalidUsernameRequest)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("username")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(rawPassword));
    }

    // ---------- 로그인 ----------

    @Test
    void 정상_로그인은_200과_게이트웨이가_검증할_수_있는_토큰() throws Exception {
        // given
        String registerJson = register(validBody(USERNAME, EMAIL))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = userIdOf(registerJson);

        // when
        String loginJson = login(USERNAME, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andReturn().getResponse().getContentAsString();

        // then
        assertThat(loginJson).doesNotContain(PASSWORD);
        String accessToken = objectMapper.readTree(loginJson).path("data").path("accessToken").asText();
        Claims claims = TestJwt.parse(JWT_SECRET, accessToken).getPayload();
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.get("role")).isEqualTo("USER");
    }

    @Test
    void 비밀번호가_틀리면_401_INVALID_CREDENTIALS() throws Exception {
        // given
        register(validBody(USERNAME, EMAIL)).andExpect(status().isCreated());

        // when & then
        login(USERNAME, "Wrong123!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 아이디가_없으면_비밀번호가_틀린_경우와_같은_401() throws Exception {
        // given
        register(validBody(USERNAME, EMAIL)).andExpect(status().isCreated());
        String wrongPasswordJson = login(USERNAME, "Wrong123!")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // when
        String unknownUserJson = login("nobody", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // then
        assertThat(unknownUserJson).isEqualTo(wrongPasswordJson);
    }

    @Test
    void 삭제된_회원은_로그인할_수_없다() throws Exception {
        // given
        register(validBody(USERNAME, EMAIL)).andExpect(status().isCreated());
        softDelete(userRepository.findAll().get(0));

        // when & then
        login(USERNAME, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 로그인_필수값이_없으면_400() throws Exception {
        // when & then
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "password")));
    }

    // ---------- 공통 준비 ----------

    private ResultActions register(Map<String, ?> request) throws Exception {
        return mockMvc.perform(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private Map<String, Object> validBody(String username, String email) {
        return Map.of("username", username, "password", PASSWORD, "slackId", "U12345678", "email", email);
    }

    // 정상 가입 요청에서 한 필드만 바꾼 요청
    private Map<String, Object> validBodyWith(String field, Object value) {
        Map<String, Object> request = new HashMap<>(validBody(USERNAME, EMAIL));
        request.put(field, value);
        return request;
    }

    private String userIdOf(String responseJson) throws Exception {
        return objectMapper.readTree(responseJson).path("data").path("userId").asText();
    }

    private void softDelete(User user) {
        user.delete(UUID.randomUUID());
        userRepository.saveAndFlush(user);
    }
}
