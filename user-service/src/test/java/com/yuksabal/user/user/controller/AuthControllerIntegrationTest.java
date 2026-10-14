package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.entity.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
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
    private static final String PASSWORD = "Strong123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

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
    void 오류_응답에_비밀번호가_노출되지_않는다() throws Exception {
        register(Map.of("username", "ab", "password", "SecretPw1!", "slackId", "U1", "email", "a@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("username")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("SecretPw1!"));
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
