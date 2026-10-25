package com.yuksabal.hub.inventory.application.dto;

import java.util.UUID;

// 재고 입고 명령 (서비스 입력, #284). 값 검증(필수·1 이상)은 요청 DTO에서 끝났다
public record InventoryInboundCommand(
        UUID hubId,
        UUID productId,
        int quantity
) {
}
