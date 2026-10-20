package com.yuksabal.company.global.client.dto.response;

// hub 재고 확인 응답 (#146). 삭제·관리 허브 변경 가능 여부 판단에 hasStock만 쓴다 (나머지 필드는 무시된다)
// hasStock = 허브에 남은 수량이 0보다 큰 재고가 있음 (수량 0인 재고는 이력으로 남고 false)
// Boolean: 응답에 필드가 없으면 null로 받아 "확인하지 못함"(502)으로 막는다. boolean이면 false로 채워져 확인 없이 통과한다
public record InventoryStockResponse(
        Boolean hasStock
) {
}
