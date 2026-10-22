package com.yuksabal.hub.inventory.domain;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;

import java.util.List;
import java.util.UUID;

// 재고 입고 판단에 쓰는 상품 정보 (company-service에서 가져온 값). company 응답 형식(ProductResponse)과 분리해, 응답이 바뀌어도 도메인은 그대로 둔다
// hubId는 상품 업체의 관리 허브
public record ProductInfo(
        UUID productId,
        UUID companyId,
        UUID hubId,
        boolean active
) {

    // 입고 규칙: 비활성 상품은 입고할 수 없고, 상품 재고는 업체 관리 허브 한 곳에만 둔다 (422)
    // 재고 행이 이미 있든 새로 만들든 입고마다 확인해야 하므로, 행을 만들 때(Inventory.create)가 아니라 입고 흐름에서 먼저 부른다 (#175)
    // 업체를 비활성화하면 소속 상품도 비활성화되므로 상품 active만 보면 비활성 업체 상품도 막힌다
    public void checkInboundTo(UUID requestHubId) {
        if (!active) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "비활성 상품은 입고할 수 없습니다.",
                    "productId");
        }
        if (!requestHubId.equals(hubId)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "상품 업체의 관리 허브에만 입고할 수 있습니다.",
                    List.of(new ApiErrorDetail("hubId", "상품 업체의 관리 허브는 " + hubId + "입니다.")));
        }
    }
}
