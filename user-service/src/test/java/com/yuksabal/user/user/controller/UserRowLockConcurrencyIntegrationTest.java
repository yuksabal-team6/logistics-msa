package com.yuksabal.user.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.support.TestJwt;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

// MASTER의 비활성화와 본인 프로필 수정이 겹쳐도 비활성화가 덮어써지지 않는다 (User는 모든 컬럼을 다시 쓴다)
// 두 쓰기 모두 회원 행을 잠그고 읽으므로 차례로 실행되고, 뒤 요청은 앞 요청이 커밋한 값을 본다
// 순서를 고정하려고 테스트 트랜잭션이 회원 행을 먼저 잠근 채로 두 요청을 보낸 뒤 잠금을 푼다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserRowLockConcurrencyIntegrationTest {

    private static final String CHANGED_SLACK_ID = "U99999999";

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
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private DeliveryClient deliveryClient;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private User master;
    private User member;

    @BeforeEach
    void setUp() {
        userRepository.deleteAllInBatch();
        master = save("master01", UserRole.MASTER);
        member = save("member01", UserRole.USER);
        given(deliveryClient.getInProgressDelivery(any(UUID.class))).willAnswer(invocation ->
                ApiResponse.success(new DeliveryInProgressResponse(invocation.getArgument(0), false)));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 비활성화와_본인_수정이_겹쳐도_비활성화가_덮어써지지_않는다() throws Exception {
        // given: 테스트 트랜잭션이 회원 행을 잠근다
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    userRepository.findByIdForUpdate(member.getId());
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // when: 비활성화 → 본인 수정 순서로 보내고 잠금을 푼다
        Future<MvcResult> deactivate = send(patch("/api/v1/users/" + member.getId() + "/status")
                .header("X-User-Id", master.getId().toString())
                .header("X-User-Role", UserRole.MASTER.name())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("active", false))));
        Thread.sleep(300);
        Future<MvcResult> updateMe = send(patch("/api/v1/auth/me")
                .header("X-User-Id", member.getId().toString())
                .header("X-User-Role", UserRole.USER.name())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("slackId", CHANGED_SLACK_ID))));
        Thread.sleep(300);
        // 두 요청 모두 잠금을 기다리고 있어야 한다
        assertThat(deactivate.isDone()).isFalse();
        release.countDown();
        lockHolder.get(10, TimeUnit.SECONDS);

        // then: 어느 쪽이 먼저 처리되든 비활성화는 남는다
        int deactivateStatus = deactivate.get(10, TimeUnit.SECONDS).getResponse().getStatus();
        int updateMeStatus = updateMe.get(10, TimeUnit.SECONDS).getResponse().getStatus();
        User saved = userRepository.findById(member.getId()).orElseThrow();
        assertThat(deactivateStatus).isEqualTo(200);
        assertThat(saved.isActive()).isFalse();
        // 본인 수정은 먼저 처리됐으면 200(값 반영), 비활성화 뒤였으면 403(값 그대로)
        assertThat(updateMeStatus).isIn(200, 403);
        assertThat(saved.getSlackId()).isEqualTo(updateMeStatus == 200 ? CHANGED_SLACK_ID : "UMEMBER01");
    }

    // 활성 MASTER 두 명이 서로를 동시에 비활성화하면 잠금 밖 확인(활성 MASTER 2명)은 둘 다 통과한다
    // hold가 활성 MASTER 전체를 잠근 뒤 다시 세므로 뒤 요청은 422가 되고 활성 MASTER는 1명 남는다
    @Test
    void 두_MASTER가_서로를_동시에_비활성화해도_활성_MASTER_한_명은_남는다() throws Exception {
        // given: 테스트 트랜잭션이 활성 MASTER 전체를 잠근다
        User otherMaster = save("master02", UserRole.MASTER);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    userRepository.findActiveByRoleForUpdate(UserRole.MASTER);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // when: 서로를 비활성화하는 두 요청을 보내고 잠금을 푼다
        Future<MvcResult> first = send(deactivate(master, otherMaster));
        Future<MvcResult> second = send(deactivate(otherMaster, master));
        Thread.sleep(300);
        // 두 요청 모두 잠금 밖 확인을 통과해 잠금을 기다리고 있어야 한다
        assertThat(first.isDone()).isFalse();
        assertThat(second.isDone()).isFalse();
        release.countDown();
        lockHolder.get(10, TimeUnit.SECONDS);

        // then: 한 요청만 성공한다
        int firstStatus = first.get(10, TimeUnit.SECONDS).getResponse().getStatus();
        int secondStatus = second.get(10, TimeUnit.SECONDS).getResponse().getStatus();
        assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 422);
        assertThat(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).isEqualTo(1);
    }

    private Future<MvcResult> send(RequestBuilder request) {
        return executor.submit(() -> mockMvc.perform(request).andReturn());
    }

    private RequestBuilder deactivate(User requester, User target) throws Exception {
        return patch("/api/v1/users/" + target.getId() + "/status")
                .header("X-User-Id", requester.getId().toString())
                .header("X-User-Role", UserRole.MASTER.name())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("active", false)));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private User save(String username, UserRole role) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuuF7a0hJt8q4lH4mQGq3e0Wv0C9oS4w3y")
                .email(username + "@example.com")
                .slackId("U" + username.toUpperCase())
                .role(role)
                .build());
    }
}
