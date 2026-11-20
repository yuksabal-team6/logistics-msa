package com.yuksabal.delivery.support;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.delivery.domain.DeliveryRepository;
import com.yuksabal.delivery.delivery.infrastructure.HubClient;
import com.yuksabal.delivery.delivery.infrastructure.OrderClient;
import com.yuksabal.delivery.delivery.infrastructure.UserClient;
import com.yuksabal.delivery.delivery.infrastructure.dto.HubRouteResponse;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// Service(또는 MockMvc로 Controller) → DB(PostgreSQL 컨테이너)까지 실제로 실행하고, 다른 서비스(Feign)만 가짜로 둔다 (hub-service IntegrationTestSupport와 같은 구성)
// H2 대신 PostgreSQL: schema.sql의 부분 유니크 인덱스(uk_deliveries_order_id, WHERE deleted_at IS NULL)를 그대로 쓴다
// Docker가 없으면 이 클래스를 상속한 테스트는 건너뛴다
// 테스트마다 배송 테이블을 비운다 (서비스가 트랜잭션을 직접 나눠 @Transactional 롤백을 쓸 수 없다)
// DeliveryRepository는 SpyBean: 기본은 실제 동작, 경합 시나리오에서만 조회 직후에 멈추는 지점을 끼운다 (Mockito가 테스트마다 초기화)
// 리포지토리 SpyBean은 실제 객체로 위임하는 mock이다(JDK 프록시). doAnswer에서 callRealMethod() 대신 기본 응답(getDefaultAnswer)을 부른다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestSupport {

    // 모든 테스트 클래스가 컨테이너 하나를 같이 쓴다. JVM이 끝나면 Testcontainers(Ryuk)가 정리한다
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    // 경합 대기 상한. 정상이면 수 ms 안에 풀리고, 넘으면 테스트 실패로 끝낸다 (무한 대기 방지)
    protected static final long WAIT_SECONDS = 10;

    protected static final UUID DEPARTURE_HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    protected static final UUID DESTINATION_HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    // 게이트웨이를 거치지 않으므로 X-User-Id·X-User-Role 헤더를 직접 넣는다
    @Autowired
    protected MockMvc mockMvc;

    @MockBean
    protected HubClient hubClient;
    @MockBean
    protected OrderClient orderClient;
    @MockBean
    protected UserClient userClient;
    @SpyBean
    protected DeliveryRepository deliveryRepository;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM delivery_service.p_delivery_route_logs");
        jdbcTemplate.update("DELETE FROM delivery_service.p_deliveries");
    }

    // ---- 허브 경로 조회 ----

    // 허브 경로 조회를 latch로 붙잡는다. k번째로 들어온 호출은 entered를 세고 open(k)·openAll()까지 멈춘다
    // expectedCalls를 넘는 호출은 멈추지 않는다
    protected HubGate holdHubRoute(int expectedCalls) {
        HubGate gate = new HubGate(expectedCalls);
        given(hubClient.getHubRoute(any(), any())).willAnswer(invocation -> {
            int index = gate.calls.getAndIncrement();
            gate.entered.countDown();
            gate.awaitRelease(index);
            return ApiResponse.success(route());
        });
        return gate;
    }

    // 붙잡지 않고 바로 응답하는 허브 (호출 횟수만 센다)
    protected HubGate passHubRoute() {
        return holdHubRoute(0);
    }

    private HubRouteResponse route() {
        return new HubRouteResponse(UUID.randomUUID(), DEPARTURE_HUB_ID, DESTINATION_HUB_ID,
                new BigDecimal("325.00"), 240);
    }

    public static final class HubGate {
        private final CountDownLatch entered;
        private final CountDownLatch[] releases;
        private final AtomicInteger calls = new AtomicInteger();

        private HubGate(int expectedCalls) {
            this.entered = new CountDownLatch(expectedCalls);
            this.releases = new CountDownLatch[expectedCalls];
            for (int i = 0; i < expectedCalls; i++) {
                releases[i] = new CountDownLatch(1);
            }
        }

        public int calls() {
            return calls.get();
        }

        // 붙잡을 호출이 모두 허브 호출에 들어올 때까지 기다린다
        public void awaitEntered() throws InterruptedException {
            if (!entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("허브 호출에 들어온 생성 요청 수가 모자란다. 남은 수=" + entered.getCount());
            }
        }

        // index번째로 들어온 호출을 푼다
        public void open(int index) {
            releases[index].countDown();
        }

        public void openAll() {
            for (CountDownLatch release : releases) {
                release.countDown();
            }
        }

        private void awaitRelease(int index) throws InterruptedException {
            if (index >= releases.length) {
                return;
            }
            if (!releases[index].await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 허브 응답을 풀어 주지 않았다. index=" + index);
            }
        }
    }

    // ---- DB 확인 ----

    // 같은 order_id의 모든 행 (soft delete 포함). 실패 메시지와 결과 기록에 쓴다
    protected List<Map<String, Object>> rowsOf(UUID orderId) {
        return jdbcTemplate.queryForList(
                "SELECT delivery_id, delivery_status, deleted_at, departure_hub_id"
                        + " FROM delivery_service.p_deliveries WHERE order_id = ? ORDER BY created_at",
                orderId);
    }

    // 삭제되지 않았고 취소되지 않은 배송 = 살아 있는 배송
    protected int activeDeliveryCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM delivery_service.p_deliveries"
                        + " WHERE order_id = ? AND deleted_at IS NULL AND delivery_status <> 'CANCELLED'",
                Integer.class, orderId);
    }

    protected String statusOf(UUID deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM delivery_service.p_deliveries WHERE delivery_id = ?",
                String.class, deliveryId);
    }
}
