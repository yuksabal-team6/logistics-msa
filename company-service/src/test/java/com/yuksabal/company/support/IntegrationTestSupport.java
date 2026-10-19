package com.yuksabal.company.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.HubClient;
import com.yuksabal.company.global.client.OrderClient;
import com.yuksabal.company.global.client.UserClient;
import com.yuksabal.company.global.client.dto.response.HubResponse;
import com.yuksabal.company.global.client.dto.response.InventoryStockResponse;
import com.yuksabal.company.global.client.dto.response.OrderInProgressResponse;
import com.yuksabal.company.global.client.dto.response.UserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Controller → Service → DB(H2)까지 실제로 실행하고, 다른 서비스(Feign)만 가짜로 둔다
// 게이트웨이를 거치지 않으므로 X-User-Id·X-User-Role 헤더를 직접 넣는다
// 테스트마다 테이블을 비워 서로 영향을 주지 않는다 (서비스가 트랜잭션을 직접 나눠 @Transactional 롤백을 쓸 수 없다)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final UUID MASTER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    protected static final UUID HUB_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    protected static final UUID OTHER_HUB_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    protected HubClient hubClient;
    @MockBean
    protected UserClient userClient;
    @MockBean
    protected OrderClient orderClient;

    // 기본값: 허브는 모두 있음, 재고·진행 중 주문 없음. 회원은 테스트에서 owner()로 만든다
    @BeforeEach
    void defaultStubs() {
        given(hubClient.getHub(any())).willAnswer(inv -> ApiResponse.success(new HubResponse(inv.getArgument(0))));
        given(hubClient.getStockByProduct(any())).willReturn(ApiResponse.success(new InventoryStockResponse(false)));
        given(hubClient.getStockByCompany(any())).willReturn(ApiResponse.success(new InventoryStockResponse(false)));
        given(orderClient.getInProgressByProduct(any())).willReturn(ApiResponse.success(new OrderInProgressResponse(false)));
        given(orderClient.getInProgressByCompany(any())).willReturn(ApiResponse.success(new OrderInProgressResponse(false)));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM company_service.p_products");
        jdbcTemplate.update("DELETE FROM company_service.p_companies");
    }

    // user가 이 회원을 활성 COMPANY로 응답하게 한다. companyId = 소속 업체 (없으면 null)
    protected UUID owner(UUID companyId) {
        UUID ownerId = UUID.randomUUID();
        givenOwner(ownerId, companyId);
        return ownerId;
    }

    protected void givenOwner(UUID ownerId, UUID companyId) {
        given(userClient.getUser(ownerId)).willReturn(ApiResponse.success(new UserResponse(true, "COMPANY", companyId)));
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

    protected ResultActions asCompany(UUID userId, MockHttpServletRequestBuilder request) throws Exception {
        return perform(request, userId, "COMPANY");
    }

    protected MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder patchJson(String url, Object body) throws Exception {
        return patch(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder getUrl(String url) {
        return get(url);
    }

    protected MockHttpServletRequestBuilder deleteUrl(String url) {
        return delete(url);
    }

    // ---- 준비 데이터 ----

    protected Map<String, Object> companyBody(UUID ownerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", "부산 수산물 도매");
        body.put("hubId", HUB_ID.toString());
        body.put("ownerId", ownerId.toString());
        body.put("address", "부산광역시 중구 중앙대로 1");
        return body;
    }

    // MASTER로 업체를 만들고 companyId를 돌려준다
    protected UUID createCompany(Map<String, Object> body) throws Exception {
        JsonNode data = data(asMaster(postJson("/api/v1/companies", body)).andExpect(status().isCreated()));
        return UUID.fromString(data.get("companyId").asText());
    }

    protected UUID createCompany(UUID ownerId) throws Exception {
        return createCompany(companyBody(ownerId));
    }

    protected UUID createProduct(UUID companyId, String productName) throws Exception {
        Map<String, Object> body = Map.of("productName", productName, "description", "냉동", "unitPrice", 5000);
        JsonNode data = data(asMaster(postJson("/api/v1/companies/" + companyId + "/products", body))
                .andExpect(status().isCreated()));
        return UUID.fromString(data.get("productId").asText());
    }

    protected void changeCompanyStatus(UUID companyId, boolean active) throws Exception {
        asMaster(patchJson("/api/v1/companies/" + companyId + "/status", Map.of("active", active)))
                .andExpect(status().isOk());
    }

    protected void changeProductStatus(UUID productId, boolean active) throws Exception {
        asMaster(patchJson("/api/v1/products/" + productId + "/status", Map.of("active", active)))
                .andExpect(status().isOk());
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    protected <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
