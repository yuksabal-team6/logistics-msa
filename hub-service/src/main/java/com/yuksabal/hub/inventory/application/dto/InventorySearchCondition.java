package com.yuksabal.hub.inventory.application.dto;

import java.util.UUID;

// 재고 목록 검색 조건 (서비스 입력). 요청 DTO와 분리해 API 파라미터가 바뀌어도 서비스는 그대로다 (#284)
// 조건은 모두 선택이고 없으면 전체 목록
public record InventorySearchCondition(
        UUID hubId,
        UUID productId
) {
}
