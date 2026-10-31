package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.HubClient;
import com.yuksabal.user.user.entity.RefreshToken;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.RefreshTokenRepository;
import com.yuksabal.user.user.repository.UserRepository;
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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 로그인 → 재발급(rotation) → 로그아웃 흐름을 실제 컨트롤러·서비스·JPA(H2)로 확인한다
// 로그아웃의 X-User-Id는 게이트웨이가 검증된 토큰 값으로 넣는 헤더라 테스트가 대신 넣는다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRefreshIntegrationTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String USERNAME = "user01";
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
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockBean
    private HubClient hubClient;
    @MockBean
    private CompanyClient companyClient;

    private User user;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        user = userRepository.saveAndFlush(User.builder()
                .username(USERNAME)
                .slackId("U12345678")
                .email("user01@example.com")
                .password(passwordEncoder.encode(PASSWORD))
                .role(UserRole.USER)
                .build());
    }

    @Test
    void 로그인하면_Refresh_Token을_발급하고_DB에는_해시만_저장한다() throws Exception {
        // when
        JsonNode data = loginData();

        // then
        String refreshToken = data.path("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();
        assertThat(data.path("expiresIn").asLong()).isEqualTo(3600);
        assertThat(data.path("refreshExpiresIn").asLong()).isEqualTo(604800);
        assertThat(refreshTokenRepository.findAll())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getUserId()).isEqualTo(user.getId());
                    assertThat(saved.getTokenHash()).isNotEqualTo(refreshToken);
                });
    }

    @Test
    void 재발급하면_새_토큰을_주고_쓴_토큰은_다시_쓸_수_없다() throws Exception {
        // given
        String oldToken = loginData().path("refreshToken").asText();

        // when
        String body = refresh(oldToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        // then
        String newToken = data.path("refreshToken").asText();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(TestJwt.parse(JWT_SECRET, data.path("accessToken").asText()).getPayload().getSubject())
                .isEqualTo(user.getId().toString());
        expectInvalid(refresh(oldToken));
        refresh(newToken).andExpect(status().isOk());
    }

    @Test
    void 재발급한_Access_Token에는_바뀐_역할이_들어간다() throws Exception {
        // given
        String token = loginData().path("refreshToken").asText();
        user.changeRole(UserRole.MASTER);
        userRepository.saveAndFlush(user);

        // when
        String accessToken = objectMapper.readTree(refresh(token).andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // then
        assertThat(TestJwt.parse(JWT_SECRET, accessToken).getPayload().get("role")).isEqualTo("MASTER");
    }

    @Test
    void 비활성_회원은_재발급하면_403이고_쓴_토큰은_폐기된다() throws Exception {
        // given
        String token = loginData().path("refreshToken").asText();
        user.changeActive(false);
        userRepository.saveAndFlush(user);

        // when & then
        refresh(token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        assertThat(refreshTokenRepository.count()).isZero();
    }

    @Test
    void 만료된_토큰은_401() throws Exception {
        // given: 로그인으로 받은 토큰의 만료 시각을 과거로 바꾼다
        String token = loginData().path("refreshToken").asText();
        RefreshToken saved = refreshTokenRepository.findAll().get(0);
        refreshTokenRepository.deleteAllInBatch();
        refreshTokenRepository.saveAndFlush(RefreshToken.of(user.getId(), saved.getTokenHash(),
                LocalDateTime.now().minusDays(8), LocalDateTime.now().minusDays(1)));

        // when & then
        expectInvalid(refresh(token));
    }

    @Test
    void 없는_토큰은_401_빈_토큰은_400() throws Exception {
        expectInvalid(refresh("not-issued-token"));
        refresh("").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 로그아웃하면_그_토큰으로_재발급할_수_없다() throws Exception {
        // given
        String token = loginData().path("refreshToken").asText();

        // when
        logout(user.getId(), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // then
        expectInvalid(refresh(token));
    }

    @Test
    void 다른_회원의_토큰으로는_로그아웃되지_않는다() throws Exception {
        // given
        String token = loginData().path("refreshToken").asText();

        // when: 결과는 구분하지 않고 200
        logout(UUID.randomUUID(), token).andExpect(status().isOk());

        // then
        refresh(token).andExpect(status().isOk());
    }

    @Test
    void 기기마다_로그인하면_토큰이_따로_발급되고_한_기기_로그아웃은_다른_기기에_영향이_없다() throws Exception {
        // given
        String first = loginData().path("refreshToken").asText();
        String second = loginData().path("refreshToken").asText();

        // when
        logout(user.getId(), first).andExpect(status().isOk());

        // then
        expectInvalid(refresh(first));
        refresh(second).andExpect(status().isOk());
    }

    private JsonNode loginData() throws Exception {
        String body = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post(REFRESH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private ResultActions logout(UUID userId, String refreshToken) throws Exception {
        return mockMvc.perform(post(LOGOUT_URL)
                .header("X-User-Id", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private static void expectInvalid(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }
}
