package com.yuksabal.user.user.controller;

import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final UUID HUB_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HUB_B = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String ENCODED_PASSWORD = "$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y";
    // BCrypt 해시는 항상 $2로 시작한다 → 응답 어디에도 없어야 한다
    private static final String BCRYPT_PREFIX = "$2";

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
        // when
        ResultActions result = list(master);

        // then
        String responseBody = result
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
        assertThat(responseBody).doesNotContain(BCRYPT_PREFIX);
    }

    @Test
    void USER가_목록을_조회하면_403() throws Exception {
        // when & then
        expectForbidden(list(alice));
    }

    @Test
    void COMPANY가_목록을_조회하면_403() throws Exception {
        // when & then
        expectForbidden(list(bob));
    }

    @Test
    void DELIVERY가_목록을_조회하면_403() throws Exception {
        // when & then
        expectForbidden(list(carol));
    }

    @Test
    void MASTER가_아니면_검색값이_틀려도_400이_아니라_403() throws Exception {
        // when & then
        list(alice, "?role=ADMIN&sort=password,asc").andExpect(status().isForbidden());
    }

    @Test
    void 역할_헤더가_모르는_값이면_403() throws Exception {
        // when & then
        mockMvc.perform(withHeaders(get(USERS_URL), master.getId(), "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 아이디_부분_일치로_검색한다() throws Exception {
        // when & then
        list(master, "?username=ali")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].username").value(contains("alice01")));
    }

    // 아이디는 영문 소문자·숫자만 가능해서 % _ 가 든 회원은 없다 → 0건이어야 한다

    @Test
    void 아이디_검색어의_퍼센트는_와일드카드가_아니라_글자로_비교한다() throws Exception {
        // when & then
        searchByUsername("%")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 아이디_검색어의_밑줄은_와일드카드가_아니라_글자로_비교한다() throws Exception {
        // when & then
        searchByUsername("_")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 아이디_검색어가_공백뿐이면_조건_없이_조회한다() throws Exception {
        // when & then
        searchByUsername("   ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(4)));
    }

    @Test
    void 아이디_검색어의_앞뒤_공백은_무시한다() throws Exception {
        // when & then
        searchByUsername(" ali ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].username").value(contains("alice01")));
    }

    @Test
    void 허브로_검색한다() throws Exception {
        // when & then
        list(master, "?hubId=" + HUB_A)
                .andExpect(jsonPath("$.data.items[*].username").value(containsInAnyOrder("alice01", "carol01")));
    }

    @Test
    void 역할과_허브를_함께_검색한다() throws Exception {
        // when & then
        list(master, "?role=DELIVERY&hubId=" + HUB_A)
                .andExpect(jsonPath("$.data.items[*].username").value(contains("carol01")));
    }

    @Test
    void 아이디로_정렬하고_페이지를_나눈다() throws Exception {
        // when & then
        list(master, "?sort=username,asc&size=2&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].username").value(contains("carol01", "master01")))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void 허용하지_않은_정렬_필드면_400() throws Exception {
        // when & then
        list(master, "?sort=password,asc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));
    }

    @Test
    void 역할_허브_형식이_틀리거나_아이디가_너무_길면_400과_필드() throws Exception {
        // given
        String tooLongUsername = "a".repeat(101);

        // when & then
        list(master, "?role=ADMIN&hubId=not-a-uuid&username=" + tooLongUsername)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("hubId", "role", "username")));
    }

    @Test
    void 탈퇴한_회원은_목록에_없다() throws Exception {
        // given
        withdraw(bob, master);

        // when & then
        list(master)
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[*].username").value(containsInAnyOrder("master01", "alice01", "carol01")));
    }

    // ---------- GET /users/{userId} ----------

    @Test
    void MASTER는_다른_회원을_상세_조회한다() throws Exception {
        // when & then
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
        // when & then
        detail(alice, alice.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice01"));
    }

    @Test
    void COMPANY는_자기_정보를_상세_조회한다() throws Exception {
        // when & then
        detail(bob, bob.getId()).andExpect(status().isOk());
    }

    @Test
    void DELIVERY는_자기_정보를_상세_조회한다() throws Exception {
        // when & then
        detail(carol, carol.getId()).andExpect(status().isOk());
    }

    @Test
    void MASTER가_아니면_다른_회원은_403() throws Exception {
        // when & then
        detail(alice, bob.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void COMPANY가_다른_회원을_상세_조회하면_403() throws Exception {
        // when & then
        detail(bob, alice.getId()).andExpect(status().isForbidden());
    }

    @Test
    void DELIVERY가_다른_회원을_상세_조회하면_403() throws Exception {
        // when & then
        detail(carol, alice.getId()).andExpect(status().isForbidden());
    }

    @Test
    void 역할이_MASTER가_아니면_대상이_MASTER여도_403() throws Exception {
        // when & then
        detail(alice, master.getId()).andExpect(status().isForbidden());
    }

    // DB에 저장된 역할이 USER라 헤더의 MASTER는 인정하지 않는다

    @Test
    void 일반_회원이_역할_헤더만_MASTER로_바꾸면_상세_조회는_403() throws Exception {
        // when & then
        mockMvc.perform(withHeaders(get(userUrl(bob.getId())), alice.getId(), "MASTER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 일반_회원이_역할_헤더만_MASTER로_바꾸면_목록_조회는_403() throws Exception {
        // when & then
        mockMvc.perform(withHeaders(get(USERS_URL), alice.getId(), "MASTER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 임의의_UUID에_MASTER_헤더를_붙이면_상세_조회는_403() throws Exception {
        // given
        UUID unknownUserId = UUID.randomUUID();

        // when & then
        mockMvc.perform(withHeaders(get(userUrl(bob.getId())), unknownUserId, "MASTER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 임의의_UUID에_MASTER_헤더를_붙이면_목록_조회는_403() throws Exception {
        // given
        UUID unknownUserId = UUID.randomUUID();

        // when & then
        mockMvc.perform(withHeaders(get(USERS_URL), unknownUserId, "MASTER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 탈퇴한_MASTER는_다른_회원을_상세_조회할_수_없다() throws Exception {
        // given
        withdraw(master, master);

        // when & then
        detail(master, bob.getId()).andExpect(status().isForbidden());
    }

    @Test
    void 탈퇴한_MASTER는_목록을_조회할_수_없다() throws Exception {
        // given
        withdraw(master, master);

        // when & then
        list(master).andExpect(status().isForbidden());
    }

    // alice는 DB에서 USER
    @ParameterizedTest
    @ValueSource(strings = {"MASTER", "COMPANY", "DELIVERY", "MANAGER", "master"})
    void 본인_조회라도_헤더_역할이_실제_역할과_다르면_403(String headerRole) throws Exception {
        // when & then
        mockMvc.perform(withHeaders(get(userUrl(alice.getId())), alice.getId(), headerRole))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void MASTER가_본인_조회를_USER_헤더로_보내도_403() throws Exception {
        // when & then
        mockMvc.perform(withHeaders(get(userUrl(master.getId())), master.getId(), "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 역할_헤더가_없으면_400() throws Exception {
        // 헤더 자체가 없으면 common 규칙(필수 헤더 누락)대로 400. 게이트웨이를 거치면 항상 채워진다
        // when & then
        mockMvc.perform(get(userUrl(alice.getId())).header(USER_ID_HEADER, alice.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void DB_역할이_MASTER여도_헤더_역할이_MASTER가_아니면_다른_회원은_403() throws Exception {
        // 토큰 발급 당시 역할이 헤더로 오므로, 헤더와 DB가 모두 MASTER여야 MASTER 권한
        // when & then
        mockMvc.perform(withHeaders(get(userUrl(bob.getId())), master.getId(), "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void MASTER가_아니면_없는_회원_ID도_404가_아니라_403() throws Exception {
        // 다른 회원 ID의 존재 여부가 드러나지 않게 권한을 먼저 본다
        // when & then
        detail(alice, UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @Test
    void 없는_회원이면_404() throws Exception {
        // when & then
        detail(master, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void MASTER가_탈퇴한_회원을_조회하면_404() throws Exception {
        // given
        withdraw(alice, alice);

        // when & then
        detail(master, alice.getId()).andExpect(status().isNotFound());
    }

    @Test
    void 탈퇴한_회원이_본인을_조회해도_404() throws Exception {
        // given
        withdraw(alice, alice);

        // when & then
        detail(alice, alice.getId()).andExpect(status().isNotFound());
    }

    @Test
    void 회원_ID가_UUID_형식이_아니면_400() throws Exception {
        // when & then
        mockMvc.perform(withUser(get(USERS_URL + "/not-a-uuid"), master))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ---------- helpers ----------

    private User save(String username, UserRole role, UUID hubId) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password(ENCODED_PASSWORD)
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .hubId(hubId)
                .build());
    }

    private void withdraw(User target, User deletedBy) {
        target.delete(deletedBy.getId());
        userRepository.saveAndFlush(target);
    }

    private String userUrl(UUID userId) {
        return USERS_URL + "/" + userId;
    }

    // DB의 실제 역할 그대로 헤더를 넣는다
    private MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder request, User user) {
        return withHeaders(request, user.getId(), user.getRole().name());
    }

    // 헤더 값을 직접 정한다 (헤더 위조 상황용)
    private MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder request, UUID userId, String role) {
        return request.header(USER_ID_HEADER, userId.toString()).header(USER_ROLE_HEADER, role);
    }

    private ResultActions list(User requester) throws Exception {
        return list(requester, "");
    }

    private ResultActions list(User requester, String query) throws Exception {
        return mockMvc.perform(withUser(get(USERS_URL + query), requester));
    }

    private ResultActions searchByUsername(String username) throws Exception {
        return mockMvc.perform(withUser(get(USERS_URL).param("username", username), master));
    }

    private ResultActions detail(User requester, UUID userId) throws Exception {
        return mockMvc.perform(withUser(get(userUrl(userId)), requester));
    }

    private void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
