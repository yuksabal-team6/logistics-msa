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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 컨트롤러 → 서비스 → JPA(H2) → common 예외 처리까지 한 번에 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    private static final String URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
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

    @Test
    void 정상_회원가입은_201과_공통_응답_형식() throws Exception {
        UUID hubId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        String body = register(Map.of("username", "user01", "password", PASSWORD, "slackId", "U12345678",
                "email", "user01@example.com", "hubId", hubId.toString(), "companyId", companyId.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.data.username").value("user01"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID userId = UUID.fromString(objectMapper.readTree(body).path("data").path("userId").asText());
        User saved = userRepository.findById(userId).orElseThrow();
        assertThat(saved.getHubId()).isEqualTo(hubId);
        assertThat(saved.getCompanyId()).isEqualTo(companyId);
        assertThat(saved.isPublic()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isNull();
    }

    @Test
    void 비밀번호는_BCrypt_해시로_저장되고_응답에_없다() throws Exception {
        String body = register(validBody("user01", "user01@example.com"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD);
        User saved = userRepository.findAll().get(0);
        assertThat(saved.getPassword()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPassword())).isTrue();
    }

    @Test
    void 요청에_role을_넣어도_USER로_가입된다() throws Exception {
        Map<String, Object> body = new HashMap<>(validBody("user01", "user01@example.com"));
        body.put("role", "MASTER");

        register(body).andExpect(status().isCreated()).andExpect(jsonPath("$.data.role").value("USER"));
        assertThat(userRepository.findAll().get(0).getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void 이메일이_중복이면_409() throws Exception {
        register(validBody("user01", "same@example.com")).andExpect(status().isCreated());

        register(validBody("user02", "same@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("email")));
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 아이디가_중복이면_409() throws Exception {
        register(validBody("user01", "a@example.com")).andExpect(status().isCreated());

        register(validBody("user01", "b@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username")));
    }

    @Test
    void 삭제된_회원의_아이디와_이메일로는_다시_가입할_수_있다() throws Exception {
        register(validBody("user01", "user01@example.com")).andExpect(status().isCreated());
        User user = userRepository.findAll().get(0);
        user.delete(UUID.randomUUID());
        userRepository.saveAndFlush(user);

        register(validBody("user01", "user01@example.com")).andExpect(status().isCreated());
    }

    @Test
    void 필수값이_없으면_400과_누락_필드_전부() throws Exception {
        register(Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(containsInAnyOrder("username", "password", "slackId", "email")));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 아이디_비밀번호_형식이_틀리면_400() throws Exception {
        register(Map.of("username", "User!", "password", "weak", "slackId", "U1", "email", "a@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "password")));
    }

    @Test
    void hubId가_UUID_형식이_아니면_400() throws Exception {
        Map<String, Object> body = new HashMap<>(validBody("user01", "user01@example.com"));
        body.put("hubId", "not-a-uuid");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 소속_ID가_빈_문자열이면_소속_없이_가입되지_않고_400() throws Exception {
        for (String blank : new String[]{"", " "}) {
            for (String field : new String[]{"hubId", "companyId"}) {
                Map<String, Object> body = new HashMap<>(validBody("user01", "user01@example.com"));
                body.put(field, blank);

                register(body)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
            }
        }
        assertThat(userRepository.findByUsername("user01")).isEmpty();
    }

    @Test
    void 같은_키가_두_번_오면_400() throws Exception {
        String json = "{\"username\":\"user01\",\"password\":\"" + PASSWORD + "\",\"slackId\":\"U1\","
                + "\"email\":\"user01@example.com\",\"username\":\"user02\"}";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 오류_응답에_비밀번호가_노출되지_않는다() throws Exception {
        register(Map.of("username", "ab", "password", "SecretPw1!", "slackId", "U1", "email", "a@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("username")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("SecretPw1!"));
    }

    @Test
    void 정상_로그인은_200과_게이트웨이가_검증할_수_있는_토큰() throws Exception {
        String registered = register(validBody("user01", "user01@example.com"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(registered).path("data").path("userId").asText();

        String body = login(Map.of("username", "user01", "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD);
        String accessToken = objectMapper.readTree(body).path("data").path("accessToken").asText();
        Claims claims = TestJwt.parse(JWT_SECRET, accessToken).getPayload();
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.get("role")).isEqualTo("USER");
    }

    @Test
    void 비밀번호가_틀리거나_아이디가_없으면_같은_401() throws Exception {
        register(validBody("user01", "user01@example.com")).andExpect(status().isCreated());

        String wrongPassword = login(Map.of("username", "user01", "password", "Wrong123!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login(Map.of("username", "nobody", "password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    void 삭제된_회원은_로그인할_수_없다() throws Exception {
        register(validBody("user01", "user01@example.com")).andExpect(status().isCreated());
        User user = userRepository.findAll().get(0);
        user.delete(UUID.randomUUID());
        userRepository.saveAndFlush(user);

        login(Map.of("username", "user01", "password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 로그인_필수값이_없으면_400() throws Exception {
        login(Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("username", "password")));
    }

    private ResultActions login(Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> validBody(String username, String email) {
        return Map.of("username", username, "password", PASSWORD, "slackId", "U12345678", "email", email);
    }

    private ResultActions register(Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
