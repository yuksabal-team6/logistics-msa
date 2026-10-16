package com.yuksabal.user.user.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #76 GET /internal/users/{userId} (Feign 전용 내부 API, 게이트웨이 미노출)
// 다른 서비스의 Feign 호출을 가정한다. 내부 API는 사용자 권한을 검사하지 않는다 (명세 0.3)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserInternalControllerIntegrationTest {

    private static final String URL = "/internal/users/";
    private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID COMPANY = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    private User delivery;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        delivery = userRepository.saveAndFlush(User.builder()
                .username("deliver01")
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email("deliver01@example.com")
                .slackId("U0DELIVER")
                .role(UserRole.DELIVERY)
                .hubId(HUB)
                .companyId(COMPANY)
                .build());
    }

    @Test
    void 회원의_ID_아이디_활성_여부_역할_소속을_공통_응답으로_돌려준다() throws Exception {
        getUser(delivery.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
                .andExpect(jsonPath("$.data.userId").value(delivery.getId().toString()))
                .andExpect(jsonPath("$.data.username").value("deliver01"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.role").value("DELIVERY"))
                .andExpect(jsonPath("$.data.hubId").value(HUB.toString()))
                .andExpect(jsonPath("$.data.companyId").value(COMPANY.toString()));
    }

    @Test
    void 응답에는_내부_계약_필드만_있고_비밀번호_등_개인_정보는_없다() throws Exception {
        String body = getUser(delivery.getId())
                .andExpect(jsonPath("$.data.*", org.hamcrest.Matchers.hasSize(6)))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.slackId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("$2a$").doesNotContain("deliver01@example.com");
    }

    @Test
    void 소속이_없으면_null로_돌려준다() throws Exception {
        User user = userRepository.saveAndFlush(User.builder().username("user01").password("$2a$hash")
                .email("user01@example.com").slackId("U1").role(UserRole.USER).build());

        getUser(user.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.hubId").doesNotExist())
                .andExpect(jsonPath("$.data.companyId").doesNotExist());
    }

    @Test
    void 비활성_회원도_404가_아니라_active_false로_돌려준다() throws Exception {
        delivery.changeActive(false);
        userRepository.saveAndFlush(delivery);

        getUser(delivery.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.role").value("DELIVERY"));
    }

    @Test
    void 없는_회원은_404() throws Exception {
        getUser(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("userId")));
    }

    @Test
    void 탈퇴_소프트_삭제한_회원은_404() throws Exception {
        delivery.delete(UUID.randomUUID());
        userRepository.saveAndFlush(delivery);

        getUser(delivery.getId()).andExpect(status().isNotFound());
    }

    @Test
    void 회원_ID가_UUID_형식이_아니면_400() throws Exception {
        mockMvc.perform(get(URL + "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 내부_API는_사용자_헤더_없이_호출된다() throws Exception {
        // Feign 호출은 FeignHeaderInterceptor가 X-User-*를 넘기지만, 내부 API는 사용자 권한을 보지 않는다 (명세 0.3).
        // 외부 접근 차단은 게이트웨이 미노출로 한다 (api-gateway 테스트: 내부_API_경로는_외부에서_404)
        getUser(delivery.getId()).andExpect(status().isOk());
        mockMvc.perform(get(URL + delivery.getId()).header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER"))
                .andExpect(status().isOk());
    }

    private ResultActions getUser(UUID userId) throws Exception {
        return mockMvc.perform(get(URL + userId));
    }
}
