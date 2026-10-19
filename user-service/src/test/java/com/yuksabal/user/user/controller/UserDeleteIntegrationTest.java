package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.OrderClient;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.client.dto.OrderInProgressResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// [직접 호출 테스트] #68 DELETE /api/v1/users/{userId} (MASTER 전용, 소프트 삭제)
// 진행 중 업무 확인은 모두 "없음"으로 두고 삭제 자체의 동작을 본다 (업무가 있을 때는 UserDeleteActiveWorkIntegrationTest)
class UserDeleteIntegrationTest {

    private static final String PASSWORD = "Strong123!";
    private static final String USERS_URL = "/api/v1/users/";

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class 삭제 extends Fixture {

        @Autowired
        private JdbcTemplate jdbcTemplate;
        @MockBean
        private OrderClient orderClient;
        @MockBean
        private CompanyClient companyClient;
        @MockBean
        private DeliveryClient deliveryClient;

        // 진행 중 주문·배송 없음 (CompanyClient 목은 빈 응답이지만 COMPANY 회원을 삭제하지 않아 호출되지 않는다)
        @BeforeEach
        void noActiveWork() {
            given(orderClient.getInProgressOrder(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                    new OrderInProgressResponse(invocation.getArgument(0), false)));
            given(deliveryClient.getInProgressDelivery(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                    new DeliveryInProgressResponse(invocation.getArgument(0), false)));
        }

        @Test
        void MASTER가_삭제하면_200이고_행은_남아_있고_삭제_시각과_삭제자가_기록된다() throws Exception {
            // when
            ResultActions result = deleteBy(master, alice.getId());

            // then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("회원이 삭제되었습니다."))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // @SQLRestriction을 거치지 않고 실제 행 확인 (물리 삭제가 아님)
            Map<String, Object> userRow = jdbcTemplate.queryForMap(
                    "select deleted_at, deleted_by, is_active from user_service.p_users where user_id = ?", alice.getId());
            assertThat(userRow.get("deleted_at")).isNotNull();
            assertThat(userRow.get("deleted_by")).isEqualTo(master.getId());
            // 확인 전에 비활성화한 상태 그대로 삭제된다
            assertThat(userRow.get("is_active")).isEqualTo(false);
            assertThat(userRepository.findById(alice.getId())).isEmpty();
        }

        @Test
        void 이미_삭제된_회원을_다시_삭제하면_404() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            deleteBy(master, alice.getId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        void USER가_삭제하면_403이고_삭제되지_않는다() throws Exception {
            // when & then
            deleteBy(alice, bob.getId()).andExpect(status().isForbidden());
            assertThat(userRepository.findById(bob.getId())).isPresent();
        }

        @Test
        void USER가_역할_헤더를_MASTER로_보내도_403이고_삭제되지_않는다() throws Exception {
            // when & then
            mockMvc.perform(delete(USERS_URL + bob.getId())
                            .header("X-User-Id", alice.getId().toString())
                            .header("X-User-Role", "MASTER"))
                    .andExpect(status().isForbidden());
            assertThat(userRepository.findById(bob.getId())).isPresent();
        }

        @Test
        void 없는_회원을_삭제하면_404() throws Exception {
            // when & then
            deleteBy(master, UUID.randomUUID()).andExpect(status().isNotFound());
        }

        @Test
        void 자기_자신을_삭제하면_422() throws Exception {
            // when & then
            deleteBy(master, master.getId())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        }

        @Test
        void 다른_MASTER도_삭제할_수_있다() throws Exception {
            // when & then
            deleteBy(master, master2.getId()).andExpect(status().isOk());
        }

        @Test
        void 비활성_회원도_삭제할_수_있다() throws Exception {
            // given
            changeStatus(master, bob.getId(), false).andExpect(status().isOk());

            // when & then
            deleteBy(master, bob.getId()).andExpect(status().isOk());
        }

        @Test
        void 삭제된_회원은_목록에_나오지_않는다() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(withUser(get("/api/v1/users"), master))
                    .andExpect(jsonPath("$.data.items[?(@.username == 'alice01')]").isEmpty());
        }

        @Test
        void 삭제된_회원을_조회하면_404() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(withUser(get(USERS_URL + alice.getId()), master))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_회원_정보를_수정하면_404() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(withUser(patch(USERS_URL + alice.getId()), master).content(json(Map.of("slackId", "U1"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_회원의_역할을_바꾸면_404() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(withUser(patch(USERS_URL + alice.getId() + "/role"), master)
                            .content(json(Map.of("role", "COMPANY"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_회원의_상태를_바꾸면_404() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            changeStatus(master, alice.getId(), true).andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_회원이_로그인하면_401() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", "alice01", "password", PASSWORD))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        }

        // 삭제 전에 받은 토큰의 헤더로 요청해도 요청자로 인정되지 않는다
        @Test
        void 삭제된_회원이_다른_회원을_조회하면_403() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(withUser(get(USERS_URL + bob.getId()), alice))
                    .andExpect(status().isForbidden());
        }

        @Test
        void 삭제된_회원이_내_정보를_조회하면_404() throws Exception {
            // given
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            // when & then
            mockMvc.perform(withUser(get("/api/v1/auth/me"), alice))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_MASTER는_관리_API를_쓸_수_없다() throws Exception {
            // given
            deleteBy(master, master2.getId()).andExpect(status().isOk());

            // when & then
            deleteBy(master2, alice.getId()).andExpect(status().isForbidden());
        }
    }

    // ---------- 공통 준비 ----------

    abstract static class Fixture {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
        }

        @Autowired
        MockMvc mockMvc;
        @Autowired
        ObjectMapper objectMapper;
        @Autowired
        UserRepository userRepository;
        @Autowired
        PasswordEncoder passwordEncoder;

        User master;
        User master2;
        User alice;
        User bob;

        @BeforeEach
        void setUp() {
            userRepository.deleteAllInBatch();
            master = save("master01", UserRole.MASTER);
            master2 = save("master02", UserRole.MASTER);
            alice = save("alice01", UserRole.USER);
            bob = save("bob01", UserRole.USER);
        }

        User save(String username, UserRole role) {
            return userRepository.saveAndFlush(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(PASSWORD))
                    .email(username + "@example.com")
                    .slackId("U" + username)
                    .role(role)
                    .build());
        }

        MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder request, User user) {
            return request.header("X-User-Id", user.getId().toString())
                    .header("X-User-Role", user.getRole().name())
                    .contentType(MediaType.APPLICATION_JSON);
        }

        String json(Map<String, ?> fields) throws Exception {
            return objectMapper.writeValueAsString(fields);
        }

        ResultActions deleteBy(User requester, UUID userId) throws Exception {
            return mockMvc.perform(withUser(delete(USERS_URL + userId), requester));
        }

        ResultActions changeStatus(User requester, UUID userId, boolean active) throws Exception {
            return mockMvc.perform(withUser(patch(USERS_URL + userId + "/status"), requester)
                    .content(json(Map.of("active", active))));
        }
    }
}
