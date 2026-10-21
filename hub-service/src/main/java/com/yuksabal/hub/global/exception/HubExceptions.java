package com.yuksabal.hub.global.exception;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;

import java.util.List;

// hub-service 여러 곳에서 반복되는 예외 객체를 만든다. 같은 예외의 에러 코드·메시지·details field를 여기서 한 번만 정한다
// HubErrorCode는 에러 이름·상태·기본 메시지 목록이고, 여기는 그 목록으로 실제 BusinessException을 만드는 곳이다
// 404는 리소스마다 에러 코드를 만들지 않아(RESOURCE_NOT_FOUND 공통) 메시지·field 조합에 여기서 이름을 붙인다
// 한 곳에서만 쓰는 업무 규칙 예외(비활성 상품 입고 등)는 규칙이 있는 자리에서 직접 만든다
public final class HubExceptions {

    private HubExceptions() {
    }

    // ---- 404 ----

    public static BusinessException hubNotFound() {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 허브입니다.", "hubId");
    }

    // field: 재고 ID로 찾았으면 "inventoryId", 상품으로 찾았으면 "productId"
    public static BusinessException inventoryNotFound(String field) {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 재고입니다.", field);
    }

    // 주문 재고 차감·복원에서 요청한 허브에 그 상품 재고가 없을 때
    public static BusinessException stockNotFound() {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "해당 허브에 상품 재고가 없습니다.", "productId");
    }

    // company-service에 상품이 없을 때 (Feign 404 변환)
    public static BusinessException productNotFound() {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 상품입니다.", "productId");
    }

    // 출발·도착 허브로 찾을 때는 어느 허브가 문제인지 특정할 수 없어 field 없이 응답한다
    public static BusinessException hubRouteNotFound() {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 허브 경로입니다.");
    }

    public static BusinessException hubRouteNotFound(String field) {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않는 허브 경로입니다.", field);
    }

    // ---- 현재 수량 detail ----

    // 재고 수량 때문에 실패한 409(INSUFFICIENT_STOCK, INVENTORY_QUANTITY_CONFLICT)에 현재 수량을 담는다 (다시 바뀔 수 있어 참고용)
    // 409는 HubErrorCode에 이름이 있어 에러별 메서드를 두지 않고, 호출하는 쪽이 에러 코드를 넘긴다
    public static BusinessException withCurrentQuantity(HubErrorCode errorCode, int currentQuantity) {
        return new BusinessException(
                errorCode,
                errorCode.getMessage(),
                List.of(new ApiErrorDetail("quantity", "현재 재고는 " + currentQuantity + "개입니다.")));
    }
}
