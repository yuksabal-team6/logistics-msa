package com.yuksabal.company.global.activework;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.HubClient;
import com.yuksabal.company.global.client.OrderClient;
import com.yuksabal.company.global.client.dto.response.InventoryStockResponse;
import com.yuksabal.company.global.client.dto.response.OrderInProgressResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// hub(#146)·order(#143) 내부 API 응답을 해석하는 부분만 확인한다. 실제 호출은 통합 확인으로 검증한다
class ActiveWorkCheckerTest {

    private static final UUID ID = UUID.randomUUID();

    private final HubClient hubClient = mock(HubClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final InventoryActiveWorkChecker inventoryChecker = new InventoryActiveWorkChecker(hubClient);
    private final OrderActiveWorkChecker orderChecker = new OrderActiveWorkChecker(orderClient);
    // Feign과 같이 모르는 필드는 무시하고 읽는다
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void 대상에_맞는_API로_재고를_확인한다() {
        given(hubClient.getStockByProduct(ID)).willReturn(ApiResponse.success(new InventoryStockResponse(true)));
        given(hubClient.getStockByCompany(ID)).willReturn(ApiResponse.success(new InventoryStockResponse(false)));

        assertThat(inventoryChecker.findBlockers(ActiveWorkTarget.PRODUCT, ID)).containsExactly("허브에 재고가 남아 있습니다.");
        assertThat(inventoryChecker.findBlockers(ActiveWorkTarget.COMPANY, ID)).isEmpty();
    }

    @Test
    void 대상에_맞는_API로_진행_중_주문을_확인한다() {
        given(orderClient.getInProgressByProduct(ID)).willReturn(ApiResponse.success(new OrderInProgressResponse(false)));
        given(orderClient.getInProgressByCompany(ID)).willReturn(ApiResponse.success(new OrderInProgressResponse(true)));

        assertThat(orderChecker.findBlockers(ActiveWorkTarget.PRODUCT, ID)).isEmpty();
        assertThat(orderChecker.findBlockers(ActiveWorkTarget.COMPANY, ID)).containsExactly("진행 중인 주문이 있습니다.");
    }

    @Test
    void 응답이_비어_있으면_확인하지_못한_것으로_보고_예외() {
        given(hubClient.getStockByProduct(ID)).willReturn(ApiResponse.success(null));
        given(orderClient.getInProgressByCompany(ID)).willReturn(null);

        assertThatThrownBy(() -> inventoryChecker.findBlockers(ActiveWorkTarget.PRODUCT, ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE));
        assertThatThrownBy(() -> orderChecker.findBlockers(ActiveWorkTarget.COMPANY, ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE));
    }

    @Test
    void 응답에_확인_필드가_없으면_확인하지_못한_것으로_보고_예외() throws Exception {
        // 상대가 필드 이름을 다르게 응답한 경우 (예: hasStock 대신 inStock). 기본형 boolean이면 false로 읽혀 통과했다
        ApiResponse<InventoryStockResponse> renamed = objectMapper.readValue(
                "{\"success\":true,\"data\":{\"inStock\":true}}", new TypeReference<>() {});
        ApiResponse<OrderInProgressResponse> missing = objectMapper.readValue(
                "{\"success\":true,\"data\":{\"companyId\":\"" + ID + "\"}}", new TypeReference<>() {});
        given(hubClient.getStockByCompany(ID)).willReturn(renamed);
        given(orderClient.getInProgressByCompany(ID)).willReturn(missing);

        assertThat(renamed.data().hasStock()).isNull();
        assertThatThrownBy(() -> inventoryChecker.findBlockers(ActiveWorkTarget.COMPANY, ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE));
        assertThatThrownBy(() -> orderChecker.findBlockers(ActiveWorkTarget.COMPANY, ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE));
    }
}
