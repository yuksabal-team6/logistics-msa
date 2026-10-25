package com.yuksabal.hub.inventory.application.dto;

// 재고 수량 조정 명령 (서비스 입력, #284). expectedQuantity는 조회할 때 본 수량
// 값 검증(필수·0 이상·조회한 수량과 다름)은 요청 DTO에서 끝났다
public record InventoryAdjustCommand(
        int expectedQuantity,
        int quantity
) {
}
