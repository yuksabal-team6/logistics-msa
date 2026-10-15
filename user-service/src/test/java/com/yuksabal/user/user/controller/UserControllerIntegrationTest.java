package com.yuksabal.user.user.controller;

import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.common.auth.UserRole;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// [직접 호출 테스트] GET /api/v1/users, GET /api/v1/users/{userId}
// 게이트웨이가 넣어 주는 X-User-Id·X-User-Role을 테스트에서 직접 넣는다 → 서비스의 권한·예외 정책만 검증한다.
// JWT 인증은 검증하지 않는다 (JWT 기준 흐름은 UserDetailGatewayFlowTest, 게이트웨이 자체는 api-gateway 테스트).
// 한계: 직접 호출에서는 실제 MASTER의 UUID를 알면 헤더로 MASTER 행세가 가능하다 → 서비스 포트는 외부에 노출하지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerIntegrationTest {

    private static final String USERS_URL = "/api/v1/users";
    private static final UUID HUB_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HUB_B = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    private User master;
    private User alice;
    private User bob;
    private User carol;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER, null);
        alice = save("alice01", UserRole.USER, HUB_A);
        bob = save("bob01", UserRole.COMPANY, HUB_B);
        carol = save("carol01", UserRole.DELIVERY, HUB_A);
    }

    // ---------- GET /users ----------

    @Test
    void MASTER는_목록을_공통_페이지_형식으로_조회하고_비밀번호는_없다() throws Exception {
        String body = list(master, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items", hasSize(4)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("$2");
    }

    @Test
    void MASTER가_아니면_403() throws Exception {
        for (User user : new User[]{alice, bob, carol}) {
            list(user, "")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
    }

    @Test
    void MASTER가_아니면_검색값이_틀려도_400이_아니라_403() throws Exception {
        list(alice, "?role=ADMIN&sort=password,asc").andExpect(status().isForbidden());
    }

    @Test
    void 역할_헤더가_모르는_값이면_403() throws Exception {
        mockMvc.perform(get(USERS_URL).header("X-User-Id", master.getId().toString()).header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 아이디_부분_일치로_검색한다() throws Exception {
        list(master, "?username=ali")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].username").value(contains("alice01")));
    }

    @Test
    void 역할과_허브로_검색한다() throws Exception {
        list(master, "?hubId=" + HUB_A)
                .andExpect(jsonPath("$.data.items[*].username").value(containsInAnyOrder("alice01", "carol01")));
        list(master, "?role=DELIVERY&hubId=" + HUB_A)
                .andExpect(jsonPath("$.data.items[*].username").value(contains("carol01")));
    }

    @Test
    void 아이디로_정렬하고_페이지를_나눈다() throws Exception {
        list(master, "?sort=username,asc&size=2&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].username").value(contains("carol01", "master01")))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void 허용하지_않은_정렬_필드면_400() throws Exception {
        list(master, "?sort=password,asc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));
    }

    @Test
    void 역할_허브_형식이_틀리거나_아이디가_너무_길면_400과_필드() throws Exception {
        list(master, "?role=ADMIN&hubId=not-a-uuid&username=" + "a".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("hubId", "role", "username")));
    }

    @Test
    void 탈퇴한_회원은_목록에_없다() throws Exception {
        bob.delete(master.getId());
        userRepository.saveAndFlush(bob);

        list(master, "")
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[*].username").value(containsInAnyOrder("master01", "alice01", "carol01")));
    }

    // ---------- GET /users/{userId} ----------

    @Test
    void MASTER는_다른_회원을_상세_조회한다() throws Exception {
        detail(master, bob.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("회원을 조회했습니다."))
                .andExpect(jsonPath("$.data.userId").value(bob.getId().toString()))
                .andExpect(jsonPath("$.data.username").value("bob01"))
                .andExpect(jsonPath("$.data.role").value("COMPANY"))
                .andExpect(jsonPath("$.data.hubId").value(HUB_B.toString()))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void 본인은_자기_정보를_상세_조회한다() throws Exception {
        detail(alice, alice.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice01"));
    }

    @Test
    void MASTER가_아니면_다른_회원은_403() throws Exception {
        detail(alice, bob.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void COMPANY와_DELIVERY도_다른_회원은_403이고_본인은_200() throws Exception {
        detail(bob, alice.getId()).andExpect(status().isForbidden());
        detail(carol, alice.getId()).andExpect(status().isForbidden());
        detail(bob, bob.getId()).andExpect(status().isOk());
        detail(carol, carol.getId()).andExpect(status().isOk());
    }

    @Test
    void 역할이_MASTER가_아니면_대상이_MASTER여도_403() throws Exception {
        detail(alice, master.getId()).andExpect(status().isForbidden());
    }

    @Test
    void 일반_회원이_역할_헤더만_MASTER로_바꾸면_상세와_목록_모두_403() throws Exception {
        // DB에 저장된 역할이 USER라 헤더의 MASTER는 인정하지 않는다
        mockMvc.perform(get(USERS_URL + "/" + bob.getId())
                        .header("X-User-Id", alice.getId().toString()).header("X-User-Role", "MASTER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get(USERS_URL)
                        .header("X-User-Id", alice.getId().toString()).header("X-User-Role", "MASTER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 임의의_UUID에_MASTER_헤더를_붙이면_상세와_목록_모두_403() throws Exception {
        String randomId = UUID.randomUUID().toString();

        mockMvc.perform(get(USERS_URL + "/" + bob.getId()).header("X-User-Id", randomId).header("X-User-Role", "MASTER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS_URL).header("X-User-Id", randomId).header("X-User-Role", "MASTER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 탈퇴한_MASTER는_다른_회원과_목록을_조회할_수_없다() throws Exception {
        master.delete(master.getId());
        userRepository.saveAndFlush(master);

        detail(master, bob.getId()).andExpect(status().isForbidden());
        list(master, "").andExpect(status().isForbidden());
    }

    @Test
    void 본인_조회라도_헤더_역할이_실제_역할과_다르면_403() throws Exception {
        // alice는 DB에서 USER
        for (String role : new String[]{"MASTER", "COMPANY", "DELIVERY", "MANAGER", "master"}) {
            mockMvc.perform(get(USERS_URL + "/" + alice.getId())
                            .header("X-User-Id", alice.getId().toString()).header("X-User-Role", role))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        // MASTER가 USER로 보내도 마찬가지
        mockMvc.perform(get(USERS_URL + "/" + master.getId())
                        .header("X-User-Id", master.getId().toString()).header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 역할_헤더가_없으면_400() throws Exception {
        // 헤더 자체가 없으면 common 규칙(필수 헤더 누락)대로 400. 게이트웨이를 거치면 항상 채워진다
        mockMvc.perform(get(USERS_URL + "/" + alice.getId()).header("X-User-Id", alice.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void DB_역할이_MASTER여도_헤더_역할이_MASTER가_아니면_다른_회원은_403() throws Exception {
        // 토큰 발급 당시 역할이 헤더로 오므로, 헤더와 DB가 모두 MASTER여야 MASTER 권한
        mockMvc.perform(get(USERS_URL + "/" + bob.getId())
                        .header("X-User-Id", master.getId().toString()).header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void MASTER가_아니면_없는_회원_ID도_404가_아니라_403() throws Exception {
        // 다른 회원 ID의 존재 여부가 드러나지 않게 권한을 먼저 본다
        detail(alice, UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @Test
    void 없는_회원이면_404() throws Exception {
        detail(master, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 탈퇴한_회원이면_404_본인도_마찬가지() throws Exception {
        alice.delete(alice.getId());
        userRepository.saveAndFlush(alice);

        detail(master, alice.getId()).andExpect(status().isNotFound());
        detail(alice, alice.getId()).andExpect(status().isNotFound());
    }

    @Test
    void 회원_ID가_UUID_형식이_아니면_400() throws Exception {
        mockMvc.perform(withUser(get(USERS_URL + "/not-a-uuid"), master))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ---------- helpers ----------

    private User save(String username, UserRole role, UUID hubId) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .hubId(hubId)
                .build());
    }

    private MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder request, User user) {
        return request.header("X-User-Id", user.getId().toString()).header("X-User-Role", user.getRole().name());
    }

    private ResultActions list(User requester, String query) throws Exception {
        return mockMvc.perform(withUser(get(USERS_URL + query), requester));
    }

    private ResultActions detail(User requester, UUID userId) throws Exception {
        return mockMvc.perform(withUser(get(USERS_URL + "/" + userId), requester));
    }
}
