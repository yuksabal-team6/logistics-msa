package com.yuksabal.hub.global.exception;

import com.yuksabal.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// 반복 예외의 에러 코드·메시지·details가 API 응답 계약대로인지 확인한다
class HubExceptionsTest {

    @Test
    void 허브_없음은_404이고_field는_hubId() {
        assertNotFound(HubExceptions.hubNotFound(), "존재하지 않는 허브입니다.", "hubId");
    }

    @Test
    void 재고_없음은_404이고_field는_찾은_기준() {
        assertNotFound(HubExceptions.inventoryNotFound("inventoryId"), "존재하지 않는 재고입니다.", "inventoryId");
        assertNotFound(HubExceptions.inventoryNotFound("productId"), "존재하지 않는 재고입니다.", "productId");
    }

    @Test
    void 허브의_상품_재고_없음은_404이고_field는_productId() {
        assertNotFound(HubExceptions.stockNotFound(), "해당 허브에 상품 재고가 없습니다.", "productId");
    }

    @Test
    void 상품_없음은_404이고_field는_productId() {
        assertNotFound(HubExceptions.productNotFound(), "존재하지 않는 상품입니다.", "productId");
    }

    @Test
    void 허브_경로_없음은_404이고_field는_있을_때만_담긴다() {
        BusinessException withoutField = HubExceptions.hubRouteNotFound();
        assertThat(withoutField.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
        assertThat(withoutField.getMessage()).isEqualTo("존재하지 않는 허브 경로입니다.");
        assertThat(withoutField.getDetails()).isEmpty();

        assertNotFound(HubExceptions.hubRouteNotFound("routeId"), "존재하지 않는 허브 경로입니다.", "routeId");
    }

    // 메시지는 에러 코드의 기본 메시지 그대로 두고, details에 현재 수량만 담는다
    @ParameterizedTest
    @EnumSource(value = HubErrorCode.class, names = {"INSUFFICIENT_STOCK", "INVENTORY_QUANTITY_CONFLICT"})
    void 현재_수량_detail은_에러_코드의_메시지에_현재_수량을_담는다(HubErrorCode errorCode) {
        BusinessException e = HubExceptions.withCurrentQuantity(errorCode, 2);

        assertThat(e.getErrorCode()).isEqualTo(errorCode);
        assertThat(e.getMessage()).isEqualTo(errorCode.getMessage());
        assertThat(e.getDetails()).extracting("field", "reason").containsExactly(tuple("quantity", "현재 재고는 2개입니다."));
    }

    private void assertNotFound(BusinessException e, String message, String field) {
        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo(message);
        assertThat(e.getDetails()).extracting("field", "reason").containsExactly(tuple(field, message));
    }
}
