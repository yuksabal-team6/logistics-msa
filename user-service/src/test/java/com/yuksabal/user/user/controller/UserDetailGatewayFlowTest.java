package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.user.global.jwt.JwtProvider;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// [JWT 기준 흐름 테스트] GET /api/v1/users/{userId}
// 실제 로그인으로 받은 JWT를 게이트웨이 규칙대로 헤더로 바꿔 보낸다:
// 클라이언트가 보낸 X-User-Id·X-User-Role은 버리고, 검증된 토큰의 sub·role로 채운다 (api-gateway AuthenticationWebFilter와 같은 규칙).
// 게이트웨이 필터 자체(서명·만료 검증, 헤더 제거)는 api-gateway GatewayAuthenticationIntegrationTest가 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserDetailGatewayFlowTest {

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
    @Autowired
    private JwtProvider jwtProvider;

    private User master;
    private User userA;
    private User userB;
    private User company;
    private User delivery;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER);
        userA = save("usera01", UserRole.USER);
        userB = save("userb01", UserRole.USER);
        company = save("company01", UserRole.COMPANY);
        delivery = save("deliver01", UserRole.DELIVERY);
    }

    @Test
    void USER는_자기_UUID를_조회한다() throws Exception {
        // when & then
        viaGateway(userA.getId(), login(userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userA.getId().toString()));
    }

    @Test
    void USER_A가_USER_B를_조회하면_403() throws Exception {
        // when & then
        viaGateway(userB.getId(), login(userA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void USER_A가_헤더를_MASTER로_위조해도_토큰_기준으로_403() throws Exception {
        // given
        Map<String, String> forgedHeaders = Map.of("X-User-Id", master.getId().toString(), "X-User-Role", "MASTER");

        // when & then
        viaGateway(userB.getId(), login(userA), forgedHeaders)
                .andExpect(status().isForbidden());
    }

    @Test
    void USER_A가_회원_ID_헤더를_USER_B로_위조해도_토큰_기준으로_403() throws Exception {
        // given
        Map<String, String> forgedHeaders = Map.of("X-User-Id", userB.getId().toString());

        // when & then
        viaGateway(userB.getId(), login(userA), forgedHeaders)
                .andExpect(status().isForbidden());
    }

    @Test
    void MASTER는_다른_회원을_조회한다() throws Exception {
        // when & then
        viaGateway(userB.getId(), login(master))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userB.getId().toString()));
    }

    @Test
    void COMPANY가_다른_회원을_조회하면_403() throws Exception {
        // when & then
        viaGateway(userA.getId(), login(company)).andExpect(status().isForbidden());
    }

    @Test
    void DELIVERY가_다른_회원을_조회하면_403() throws Exception {
        // when & then
        viaGateway(userA.getId(), login(delivery)).andExpect(status().isForbidden());
    }

    @Test
    void MASTER가_없는_UUID를_조회하면_404() throws Exception {
        // when & then
        viaGateway(UUID.randomUUID(), login(master))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 일반_회원이_없는_UUID를_조회하면_403() throws Exception {
        // when & then
        viaGateway(UUID.randomUUID(), login(userA)).andExpect(status().isForbidden());
    }

    @Test
    void 탈퇴한_회원은_MASTER가_조회해도_404() throws Exception {
        // given
        String masterToken = login(master);
        userB.delete(master.getId());
        userRepository.saveAndFlush(userB);

        // when & then
        viaGateway(userB.getId(), masterToken).andExpect(status().isNotFound());
    }

    @Test
    void 토큰의_역할이_MASTER여도_DB_역할이_MASTER가_아니면_403() throws Exception {
        // given
        // 토큰 발급 뒤 역할이 바뀐 경우를 흉내 낸다 (토큰 무효화가 없어 만료 전까지 예전 역할이 담겨 있다)
        String staleMasterToken = jwtProvider.createAccessToken(userA.getId(), UserRole.MASTER);

        // when & then
        viaGateway(userB.getId(), staleMasterToken).andExpect(status().isForbidden());
    }

    @Test
    void 토큰_역할이_실제_역할과_다르면_본인_조회도_403() throws Exception {
        // given
        // 예: 토큰 발급 뒤 역할이 바뀐 경우
        String staleToken = jwtProvider.createAccessToken(userA.getId(), UserRole.MASTER);

        // when & then
        viaGateway(userA.getId(), staleToken).andExpect(status().isForbidden());
    }

    @Test
    void 다시_로그인해_새_토큰을_받으면_본인_조회가_된다() throws Exception {
        // given
        // 예전 역할이 담긴 토큰은 403이지만, 다시 로그인하면 실제 역할로 새 토큰이 나온다
        String staleToken = jwtProvider.createAccessToken(userA.getId(), UserRole.MASTER);
        viaGateway(userA.getId(), staleToken).andExpect(status().isForbidden());

        // when
        String freshToken = login(userA);

        // then
        viaGateway(userA.getId(), freshToken).andExpect(status().isOk());
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

    private String login(User user) throws Exception {
        String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", user.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).path("data").path("accessToken").asText();
    }

    private ResultActions viaGateway(UUID targetId, String token) throws Exception {
        return viaGateway(targetId, token, Map.of());
    }

    // 게이트웨이 규칙: 클라이언트 헤더(clientHeaders)는 버리고, 서명 검증된 토큰의 sub·role로 X-User-Id·X-User-Role을 설정
    private ResultActions viaGateway(UUID targetId, String token, Map<String, String> clientHeaders) throws Exception {
        Claims claims = TestJwt.parse(JWT_SECRET, token).getPayload();
        return mockMvc.perform(get("/api/v1/users/" + targetId)
                .header("X-User-Id", claims.getSubject())
                .header("X-User-Role", claims.get("role", String.class)));
    }
}
