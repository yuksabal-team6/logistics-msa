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
            deleteBy(master, alice.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("회원이 삭제되었습니다."))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // @SQLRestriction을 거치지 않고 실제 행 확인 (물리 삭제가 아님)
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select deleted_at, deleted_by, is_active from user_service.p_users where user_id = ?", alice.getId());
            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(row.get("deleted_by")).isEqualTo(master.getId());
            // 확인 전에 비활성화한 상태 그대로 삭제된다
            assertThat(row.get("is_active")).isEqualTo(false);
            assertThat(userRepository.findById(alice.getId())).isEmpty();
        }

        @Test
        void 이미_삭제된_회원을_다시_삭제하면_404() throws Exception {
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            deleteBy(master, alice.getId())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        void MASTER가_아니면_403이고_삭제되지_않는다() throws Exception {
            deleteBy(alice, bob.getId()).andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/v1/users/" + bob.getId())
                            .header("X-User-Id", alice.getId().toString()).header("X-User-Role", "MASTER"))
                    .andExpect(status().isForbidden());
            assertThat(userRepository.findById(bob.getId())).isPresent();
        }

        @Test
        void 없는_회원은_404_자기_자신은_422() throws Exception {
            deleteBy(master, UUID.randomUUID()).andExpect(status().isNotFound());
            deleteBy(master, master.getId())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        }

        @Test
        void 다른_MASTER와_비활성_회원도_삭제할_수_있다() throws Exception {
            deleteBy(master, master2.getId()).andExpect(status().isOk());

            mockMvc.perform(withUser(patch("/api/v1/users/" + bob.getId() + "/status"), master)
                    .content("{\"active\":false}")).andExpect(status().isOk());
            deleteBy(master, bob.getId()).andExpect(status().isOk());
        }

        @Test
        void 삭제된_회원은_조회_수정_역할_상태_변경_대상이_아니다() throws Exception {
            deleteBy(master, alice.getId()).andExpect(status().isOk());
            String path = "/api/v1/users/" + alice.getId();

            mockMvc.perform(withUser(get("/api/v1/users"), master))
                    .andExpect(jsonPath("$.data.items[?(@.username == 'alice01')]").isEmpty());
            mockMvc.perform(withUser(get(path), master)).andExpect(status().isNotFound());
            mockMvc.perform(withUser(patch(path), master).content("{\"slackId\":\"U1\"}")).andExpect(status().isNotFound());
            mockMvc.perform(withUser(patch(path + "/role"), master).content("{\"role\":\"COMPANY\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(withUser(patch(path + "/status"), master).content("{\"active\":true}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_회원은_로그인할_수_없고_요청자로도_인정되지_않는다() throws Exception {
            deleteBy(master, alice.getId()).andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", "alice01", "password", PASSWORD))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
            // 삭제 전에 받은 토큰의 헤더로 요청해도 요청자로 인정되지 않는다
            mockMvc.perform(withUser(get("/api/v1/users/" + bob.getId()), alice)).andExpect(status().isForbidden());
            mockMvc.perform(withUser(get("/api/v1/auth/me"), alice)).andExpect(status().isNotFound());
        }

        @Test
        void 삭제된_MASTER는_관리_API를_쓸_수_없다() throws Exception {
            deleteBy(master, master2.getId()).andExpect(status().isOk());

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

        ResultActions deleteBy(User requester, UUID userId) throws Exception {
            return mockMvc.perform(withUser(delete("/api/v1/users/" + userId), requester));
        }
    }
}
