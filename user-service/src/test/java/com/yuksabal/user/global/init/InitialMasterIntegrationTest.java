package com.yuksabal.user.global.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 환경변수 4개가 모두 있으면 기동할 때 MASTER가 만들어지고, 그 계정으로 로그인하면 role=MASTER 토큰이 나온다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InitialMasterIntegrationTest {

    private static final String MASTER_USERNAME = "master01";
    private static final String PASSWORD = "Master123!";
    private static final String JWT_SECRET = TestJwt.randomBase64Secret(32);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("initial-master.username", () -> MASTER_USERNAME);
        registry.add("initial-master.password", () -> PASSWORD);
        registry.add("initial-master.email", () -> "master@example.com");
        registry.add("initial-master.slack-id", () -> "U0MASTER01");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InitialMasterInitializer initializer;

    @Test
    void 재시작해도_MASTER는_한_번만_생성되고_비밀번호는_BCrypt_해시다() {
        // when
        // 재시작을 흉내 내 한 번 더 실행해도 늘지 않는다
        initializer.run(null);

        // then
        List<User> masters = userRepository.findAll().stream()
                .filter(user -> user.getRole() == UserRole.MASTER)
                .toList();
        assertThat(masters).hasSize(1);
        assertThat(masters.get(0).getPassword()).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void 기동_시_생성된_MASTER로_로그인하면_MASTER_토큰이_나온다() throws Exception {
        // when
        String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", MASTER_USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // then
        String token = objectMapper.readTree(responseBody).path("data").path("accessToken").asText();
        assertThat(TestJwt.parse(JWT_SECRET, token).getPayload().get("role")).isEqualTo("MASTER");
    }

    @Test
    void 일반_회원가입은_계속_USER로_고정된다() throws Exception {
        // given
        Map<String, String> registerWithMasterRole = Map.of(
                "username", "user01", "password", "Strong123!", "slackId", "U12345678",
                "email", "user01@example.com", "role", "MASTER");

        // when & then
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerWithMasterRole)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }
}
