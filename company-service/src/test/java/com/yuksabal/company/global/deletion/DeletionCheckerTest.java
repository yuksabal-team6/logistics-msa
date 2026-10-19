package com.yuksabal.company.global.deletion;

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

// 계약 가정(#137) 응답을 해석하는 부분만 확인한다. 실제 경로·필드는 hub·order API 확정 후 맞춘다
class DeletionCheckerTest {

    private static final UUID ID = UUID.randomUUID();

    private final HubClient hubClient = mock(HubClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final InventoryDeletionChecker inventoryChecker = new InventoryDeletionChecker(hubClient);
    private final OrderDeletionChecker orderChecker = new OrderDeletionChecker(orderClient);

    @Test
    void 대상에_맞는_API로_재고를_확인한다() {
        given(hubClient.getStockByProduct(ID)).willReturn(ApiResponse.success(new InventoryStockResponse(true)));
        given(hubClient.getStockByCompany(ID)).willReturn(ApiResponse.success(new InventoryStockResponse(false)));

        assertThat(inventoryChecker.findBlockers(DeletionTarget.PRODUCT, ID)).containsExactly("허브에 재고가 남아 있습니다.");
        assertThat(inventoryChecker.findBlockers(DeletionTarget.COMPANY, ID)).isEmpty();
    }

    @Test
    void 대상에_맞는_API로_진행_중_주문을_확인한다() {
        given(orderClient.getInProgressByProduct(ID)).willReturn(ApiResponse.success(new OrderInProgressResponse(false)));
        given(orderClient.getInProgressBySupplier(ID)).willReturn(ApiResponse.success(new OrderInProgressResponse(true)));

        assertThat(orderChecker.findBlockers(DeletionTarget.PRODUCT, ID)).isEmpty();
        assertThat(orderChecker.findBlockers(DeletionTarget.COMPANY, ID)).containsExactly("진행 중인 주문이 있습니다.");
    }

    @Test
    void 응답이_비어_있으면_확인하지_못한_것으로_보고_예외() {
        given(hubClient.getStockByProduct(ID)).willReturn(ApiResponse.success(null));
        given(orderClient.getInProgressBySupplier(ID)).willReturn(null);

        assertThatThrownBy(() -> inventoryChecker.findBlockers(DeletionTarget.PRODUCT, ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE));
        assertThatThrownBy(() -> orderChecker.findBlockers(DeletionTarget.COMPANY, ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DEPENDENCY_FAILURE));
    }
}
