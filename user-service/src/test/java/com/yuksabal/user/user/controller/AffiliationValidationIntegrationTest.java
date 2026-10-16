package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.HubClient;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #94 회원가입·MASTER 회원 수정의 소속 허브·업체 존재 확인 (hub #7, company #2 내부 API)
// 내부 API는 테스트 대역으로 바꾸고, Feign이 실제로 던지는 예외로 각 상황을 재현한다
// - 404 → FeignClientException(404) / 5xx → FeignServerException / 타임아웃·연결 실패 → RetryableException
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AffiliationValidationIntegrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String USERS_URL = "/api/v1/users/";
    private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID OTHER_HUB = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID COMPANY = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_COMPANY = UUID.fromString("20000000-0000-4000-8000-000000000002");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @MockBean
    private HubClient hubClient;
    @MockBean
    private CompanyClient companyClient;

    private User master;
    private User alice;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER, null, null);
        alice = save("alice01", UserRole.USER, HUB, COMPANY);
    }

    // ---------- 회원가입 ----------

    @Test
    void 있는_허브로_가입하면_201이고_허브를_확인한다() throws Exception {
        register(Map.of("hubId", HUB.toString()))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByUsername("user01").orElseThrow().getHubId()).isEqualTo(HUB);
        verify(hubClient).getHub(HUB);
        verifyNoInteractions(companyClient);
    }

    @Test
    void 없는_허브로_가입하면_400이고_저장하지_않는다() throws Exception {
        given(hubClient.getHub(HUB)).willThrow(notFound());

        register(Map.of("hubId", HUB.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("hubId")));

        assertThat(userRepository.findByUsername("user01")).isEmpty();
    }

    @Test
    void 있는_업체로_가입하면_201이고_업체를_확인한다() throws Exception {
        register(Map.of("companyId", COMPANY.toString()))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByUsername("user01").orElseThrow().getCompanyId()).isEqualTo(COMPANY);
        verify(companyClient).getCompany(COMPANY);
        verifyNoInteractions(hubClient);
    }

    @Test
    void 없는_업체로_가입하면_400이고_저장하지_않는다() throws Exception {
        given(companyClient.getCompany(COMPANY)).willThrow(notFound());

        register(Map.of("companyId", COMPANY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("companyId")));

        assertThat(userRepository.findByUsername("user01")).isEmpty();
    }

    @Test
    void 허브와_업체가_모두_없으면_둘_다_알려준다() throws Exception {
        given(hubClient.getHub(HUB)).willThrow(notFound());
        given(companyClient.getCompany(COMPANY)).willThrow(notFound());

        register(Map.of("hubId", HUB.toString(), "companyId", COMPANY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("hubId", "companyId")));
    }

    @Test
    void 소속_없이_가입하면_내부_API를_호출하지_않는다() throws Exception {
        register(Map.of()).andExpect(status().isCreated());

        verifyNoInteractions(hubClient, companyClient);
    }

    @Test
    void 허브_서비스_타임아웃이면_502이고_저장하지_않는다() throws Exception {
        given(hubClient.getHub(HUB)).willThrow(timeout());

        register(Map.of("hubId", HUB.toString()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));

        assertThat(userRepository.findByUsername("user01")).isEmpty();
    }

    @Test
    void 업체_서비스가_5xx면_502로_없는_업체와_구분한다() throws Exception {
        given(companyClient.getCompany(COMPANY))
                .willThrow(new FeignServerException(503, "SERVICE_UNAVAILABLE", "unavailable", "CompanyClient#getCompany"));

        register(Map.of("companyId", COMPANY.toString()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));
    }

    @Test
    void 내부_API의_404가_아닌_4xx는_502나_400으로_바꾸지_않는다() throws Exception {
        given(hubClient.getHub(HUB)).willThrow(new FeignClientException(403, "FORBIDDEN", "forbidden", List.of()));

        register(Map.of("hubId", HUB.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 아이디가_중복이면_소속을_확인하기_전에_409() throws Exception {
        Map<String, Object> body = registerBody(Map.of("hubId", HUB.toString()));
        body.put("username", "alice01");

        mockMvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());

        verifyNoInteractions(hubClient);
    }

    // ---------- MASTER 회원 수정 ----------

    @Test
    void MASTER가_허브를_바꾸면_새_허브만_확인한다() throws Exception {
        update(master, Map.of("hubId", OTHER_HUB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hubId").value(OTHER_HUB.toString()));

        verify(hubClient).getHub(OTHER_HUB);
        verifyNoInteractions(companyClient);
    }

    @Test
    void MASTER가_없는_허브로_바꾸면_400이고_기존_값을_유지한다() throws Exception {
        given(hubClient.getHub(OTHER_HUB)).willThrow(notFound());

        update(master, Map.of("hubId", OTHER_HUB.toString(), "slackId", "U99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("hubId")));

        User reloaded = reload(alice);
        assertThat(reloaded.getHubId()).isEqualTo(HUB);
        assertThat(reloaded.getSlackId()).isEqualTo("Ualice01");
    }

    @Test
    void MASTER가_업체를_바꾸면_새_업체만_확인한다() throws Exception {
        update(master, Map.of("companyId", OTHER_COMPANY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(OTHER_COMPANY.toString()));

        verify(companyClient).getCompany(OTHER_COMPANY);
        verifyNoInteractions(hubClient);
    }

    @Test
    void MASTER가_없는_업체로_바꾸면_400이고_기존_값을_유지한다() throws Exception {
        given(companyClient.getCompany(OTHER_COMPANY)).willThrow(notFound());

        update(master, Map.of("companyId", OTHER_COMPANY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field").value(containsInAnyOrder("companyId")));

        assertThat(reload(alice).getCompanyId()).isEqualTo(COMPANY);
    }

    @Test
    void 소속을_현재와_같은_값으로_보내면_내부_API를_호출하지_않는다() throws Exception {
        update(master, Map.of("hubId", HUB.toString(), "companyId", COMPANY.toString(), "slackId", "U99"))
                .andExpect(status().isOk());

        verifyNoInteractions(hubClient, companyClient);
    }

    @Test
    void 소속을_보내지_않으면_내부_API를_호출하지_않는다() throws Exception {
        update(master, Map.of("slackId", "U99")).andExpect(status().isOk());

        verifyNoInteractions(hubClient, companyClient);
    }

    @Test
    void 수정_중_업체_서비스_타임아웃이면_502이고_아무것도_바꾸지_않는다() throws Exception {
        given(companyClient.getCompany(OTHER_COMPANY)).willThrow(timeout());

        update(master, Map.of("hubId", OTHER_HUB.toString(), "companyId", OTHER_COMPANY.toString()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_FAILURE"));

        User reloaded = reload(alice);
        assertThat(reloaded.getHubId()).isEqualTo(HUB);
        assertThat(reloaded.getCompanyId()).isEqualTo(COMPANY);
    }

    @Test
    void MASTER가_아니면_소속을_확인하기_전에_403() throws Exception {
        update(alice, Map.of("hubId", OTHER_HUB.toString())).andExpect(status().isForbidden());

        verify(hubClient, never()).getHub(any());
        assertThat(reload(alice).getHubId()).isEqualTo(HUB);
    }

    // ---------- helpers ----------

    private FeignClientException notFound() {
        return new FeignClientException(404, "RESOURCE_NOT_FOUND", "not found", List.of());
    }

    private RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.GET, "http://hub-service/internal", Map.of(),
                null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.GET, (Long) null, request);
    }

    private User save(String username, UserRole role, UUID hubId, UUID companyId) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email(username + "@example.com")
                .slackId("U" + username)
                .role(role)
                .hubId(hubId)
                .companyId(companyId)
                .build());
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private Map<String, Object> registerBody(Map<String, String> affiliation) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "username", "user01", "password", "Strong123!", "slackId", "U12345678",
                "email", "user01@example.com"));
        body.putAll(affiliation);
        return body;
    }

    private ResultActions register(Map<String, String> affiliation) throws Exception {
        return mockMvc.perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerBody(affiliation))));
    }

    private ResultActions update(User requester, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch(USERS_URL + alice.getId())
                .header("X-User-Id", requester.getId().toString())
                .header("X-User-Role", requester.getRole().name())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
