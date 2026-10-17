package com.yuksabal.user.user.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.OrderClient;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #68 비활성화·DELIVERY 역할 변경 시 진행 중 배송 확인 (delivery 답변)
// 확인 전에 먼저 비활성화하고(다른 서비스의 새 담당자 지정을 막음), 막히면 되돌린다.
// 확인 중 다른 MASTER가 회원을 바꿨으면 그 변경을 덮어쓰지 않는다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserActiveWorkIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @MockBean
    private DeliveryClient deliveryClient;
    @MockBean
    private OrderClient orderClient;
    @MockBean
    private CompanyClient companyClient;

    private User master;
    private User courier;
    private User alice;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER);
        courier = save("courier01", UserRole.DELIVERY);
        alice = save("alice01", UserRole.USER);
        given(deliveryClient.getInProgressDelivery(any(UUID.class))).willAnswer(invocation -> ApiResponse.success(
                new DeliveryInProgressResponse(invocation.getArgument(0), false)));
    }

    // ---------- 비활성화 ----------

    @Test
    void 진행_중인_배송이_없으면_비활성화된다() throws Exception {
        active(courier, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        assertThat(reload(courier).isActive()).isFalse();
        verify(deliveryClient).getInProgressDelivery(courier.getId());
    }

    @Test
    void 진행_중인_배송이_있으면_비활성화는_409이고_활성으로_되돌린다() throws Exception {
        hasDelivery(courier);

        active(courier, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_HAS_ACTIVE_WORK"))
                .andExpect(jsonPath("$.error.message").value("진행 중인 업무가 있어 계정을 비활성화할 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("delivery"));
        assertThat(reload(courier).isActive()).isTrue();
    }

    @Test
    void 배송_확인에_실패하면_비활성화는_502이고_활성으로_되돌린다() throws Exception {
        given(deliveryClient.getInProgressDelivery(courier.getId())).willThrow(timeout());

        active(courier, false)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"))
                .andExpect(jsonPath("$.error.message").value("진행 중인 업무를 확인하지 못해 계정을 비활성화할 수 없습니다."));
        assertThat(reload(courier).isActive()).isTrue();
    }

    @Test
    void 역할과_관계없이_배송을_확인하고_주문_업체는_확인하지_않는다() throws Exception {
        hasDelivery(alice);

        active(alice, false).andExpect(status().isConflict());
        verify(orderClient, never()).getInProgressOrder(any());
        verify(companyClient, never()).getCompaniesByOwner(any());
    }

    @Test
    void 활성화는_배송을_확인하지_않는다() throws Exception {
        deactivateDirectly(courier);

        active(courier, true).andExpect(status().isOk());
        assertThat(reload(courier).isActive()).isTrue();
        verify(deliveryClient, never()).getInProgressDelivery(any());
    }

    // ---------- DELIVERY → 다른 역할 ----------

    @Test
    void 진행_중인_배송이_없으면_역할이_바뀌고_활성으로_남는다() throws Exception {
        List<User> duringCheck = new ArrayList<>();
        given(deliveryClient.getInProgressDelivery(courier.getId())).willAnswer(invocation -> {
            duringCheck.add(reload(courier));
            return ApiResponse.success(new DeliveryInProgressResponse(courier.getId(), false));
        });

        role(courier, "USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.active").value(true));
        // 확인하는 동안은 역할을 바꾸지 않고 비활성으로만 둔다
        assertThat(duringCheck).singleElement().satisfies(user -> {
            assertThat(user.isActive()).isFalse();
            assertThat(user.getRole()).isEqualTo(UserRole.DELIVERY);
        });
        assertThat(reload(courier).getRole()).isEqualTo(UserRole.USER);
        assertThat(reload(courier).isActive()).isTrue();
    }

    @Test
    void 진행_중인_배송이_있으면_역할_변경은_409이고_그대로다() throws Exception {
        hasDelivery(courier);

        role(courier, "USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_HAS_ACTIVE_WORK"))
                .andExpect(jsonPath("$.error.message").value("진행 중인 업무가 있어 역할을 변경할 수 없습니다."));
        assertThat(reload(courier).getRole()).isEqualTo(UserRole.DELIVERY);
        assertThat(reload(courier).isActive()).isTrue();
    }

    @Test
    void 배송_확인에_실패하면_역할_변경은_502이고_그대로다() throws Exception {
        given(deliveryClient.getInProgressDelivery(courier.getId())).willReturn(ApiResponse.success(null));

        role(courier, "COMPANY").andExpect(status().isBadGateway());
        assertThat(reload(courier).getRole()).isEqualTo(UserRole.DELIVERY);
        assertThat(reload(courier).isActive()).isTrue();
    }

    @Test
    void 비활성_DELIVERY는_역할이_바뀌어도_비활성으로_남는다() throws Exception {
        deactivateDirectly(courier);

        role(courier, "USER").andExpect(status().isOk());
        assertThat(reload(courier).getRole()).isEqualTo(UserRole.USER);
        assertThat(reload(courier).isActive()).isFalse();

        // 막혀도 원래 비활성이었으므로 그대로 비활성
        User other = save("courier02", UserRole.DELIVERY);
        deactivateDirectly(other);
        hasDelivery(other);
        role(other, "USER").andExpect(status().isConflict());
        assertThat(reload(other).isActive()).isFalse();
    }

    @Test
    void DELIVERY가_아닌_역할_변경은_배송을_확인하지_않는다() throws Exception {
        role(alice, "DELIVERY").andExpect(status().isOk());
        verify(deliveryClient, never()).getInProgressDelivery(any());
    }

    // ---------- 확인 중 다른 변경 ----------

    @Test
    void 확인_중_다른_MASTER가_바꿨으면_되돌리지_않는다() throws Exception {
        given(deliveryClient.getInProgressDelivery(courier.getId())).willAnswer(invocation -> {
            changeSlackIdByAnotherMaster(courier);
            return ApiResponse.success(new DeliveryInProgressResponse(courier.getId(), true));
        });

        active(courier, false).andExpect(status().isConflict());
        // 다른 변경을 덮어쓰지 않으려고 비활성 그대로 둔다 (MASTER가 다시 활성화)
        assertThat(reload(courier).isActive()).isFalse();
        assertThat(reload(courier).getSlackId()).isEqualTo("UCHANGED");
    }

    @Test
    void 확인_중_다른_MASTER가_바꿨으면_역할_변경을_멈추고_422() throws Exception {
        given(deliveryClient.getInProgressDelivery(courier.getId())).willAnswer(invocation -> {
            changeSlackIdByAnotherMaster(courier);
            return ApiResponse.success(new DeliveryInProgressResponse(courier.getId(), false));
        });

        role(courier, "USER")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("처리하는 동안 회원 정보가 변경되었습니다. 다시 시도해 주세요."));
        assertThat(reload(courier).getRole()).isEqualTo(UserRole.DELIVERY);
        assertThat(reload(courier).getSlackId()).isEqualTo("UCHANGED");
    }

    // ---------- helpers ----------

    private void hasDelivery(User user) {
        given(deliveryClient.getInProgressDelivery(user.getId()))
                .willReturn(ApiResponse.success(new DeliveryInProgressResponse(user.getId(), true)));
    }

    private void changeSlackIdByAnotherMaster(User target) {
        User user = reload(target);
        user.updateProfile(null, null, "UCHANGED", null);
        userRepository.saveAndFlush(user);
    }

    private void deactivateDirectly(User target) {
        User user = reload(target);
        user.changeActive(false);
        userRepository.saveAndFlush(user);
    }

    private RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.GET, "http://delivery-service/internal/deliveries/in-progress",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, (Long) null, request);
    }

    private ResultActions active(User target, boolean active) throws Exception {
        return mockMvc.perform(asMaster(patch("/api/v1/users/" + target.getId() + "/status"))
                .content("{\"active\":" + active + "}"));
    }

    private ResultActions role(User target, String role) throws Exception {
        return mockMvc.perform(asMaster(patch("/api/v1/users/" + target.getId() + "/role"))
                .content("{\"role\":\"" + role + "\"}"));
    }

    private MockHttpServletRequestBuilder asMaster(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", master.getId().toString())
                .header("X-User-Role", "MASTER")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private User save(String username, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .build());
    }
}
