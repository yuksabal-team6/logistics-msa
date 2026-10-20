package com.yuksabal.hub.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.client.dto.ProductResponse;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Controller → Service → DB(PostgreSQL 컨테이너)까지 실제로 실행하고, 다른 서비스(Feign)만 가짜로 둔다
// H2 대신 PostgreSQL을 쓰는 이유: schema.sql의 부분 유니크 인덱스(WHERE deleted_at IS NULL)·CHECK 제약을 그대로 확인한다
// Docker가 없으면 이 클래스를 상속한 테스트는 건너뛴다
// 게이트웨이를 거치지 않으므로 X-User-Id·X-User-Role 헤더를 직접 넣는다
// 테스트마다 재고 테이블을 비워 서로 영향을 주지 않는다 (서비스가 트랜잭션을 직접 나눠 @Transactional 롤백을 쓸 수 없다)
// 허브 17개·경로 272개는 data.sql 초기 데이터를 그대로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestSupport {

    // 모든 테스트 클래스가 컨테이너 하나를 같이 쓴다 (Spring 컨텍스트 캐시가 같은 DB를 가리키도록 @Container로 클래스마다 다시 띄우지 않는다)
    // JVM이 끝나면 Testcontainers(Ryuk)가 컨테이너를 정리한다
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    // data.sql 허브: 01 = 서울특별시 센터, 04 = 부산광역시 센터
    protected static final UUID HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID OTHER_HUB_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    protected static final UUID MASTER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    protected CompanyClient companyClient;

    // Docker가 없으면 @Testcontainers가 먼저 건너뛰어 여기까지 오지 않는다
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM hub_service.p_inventory_logs");
        jdbcTemplate.update("DELETE FROM hub_service.p_inventories");
    }

    // ---- 다른 서비스 응답 ----

    // company가 이 상품을 응답하게 한다. hubId = 상품 업체의 관리 허브
    protected void givenProduct(UUID productId, UUID companyId, UUID hubId, boolean active) {
        given(companyClient.getProduct(productId))
                .willReturn(ApiResponse.success(new ProductResponse(productId, companyId, hubId, active)));
    }

    protected void givenCompany(UUID companyId, UUID ownerId) {
        given(companyClient.getCompany(companyId))
                .willReturn(ApiResponse.success(new CompanyResponse(companyId, ownerId)));
    }

    // ---- 요청 ----

    protected ResultActions perform(MockHttpServletRequestBuilder request, UUID userId, String role) throws Exception {
        if (userId != null) {
            request.header("X-User-Id", userId.toString());
        }
        if (role != null) {
            request.header("X-User-Role", role);
        }
        return mockMvc.perform(request);
    }

    protected ResultActions asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request, MASTER_ID, "MASTER");
    }

    protected MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder patchJson(String url, Object body) throws Exception {
        return patch(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    // ---- 준비 데이터 ----

    // 활성 상품을 HUB_ID에 입고하고 inventoryId를 돌려준다
    protected UUID inbound(UUID productId, int quantity) throws Exception {
        givenProduct(productId, UUID.randomUUID(), HUB_ID, true);
        Map<String, Object> body = Map.of("hubId", HUB_ID, "productId", productId, "quantity", quantity);
        JsonNode data = data(asMaster(postJson("/api/v1/inventories/inbound", body)).andExpect(status().isOk()));
        return UUID.fromString(data.get("inventoryId").asText());
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    protected <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }

    protected void update(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
    }
}
