package com.yuksabal.company.global.client.dto.response;

// 계약 가정 (#137): hub 재고 확인 응답. 삭제 가능 여부 판단에 hasStock만 쓴다 (나머지 필드는 무시된다)
// hasStock = 허브에 남은 수량이 0보다 큰 재고가 있음 (수량 0인 재고는 이력으로 남고 false)
public record InventoryStockResponse(
        boolean hasStock
) {
}
