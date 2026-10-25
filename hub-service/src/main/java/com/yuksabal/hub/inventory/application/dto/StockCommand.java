package com.yuksabal.hub.inventory.application.dto;

import java.util.UUID;

// 주문 재고 차감·복원 명령 (서비스 입력, #284). 차감·복원이 같은 값을 받아 하나로 쓴다
// 값 검증(필수·1 이상)은 요청 DTO에서 끝났다. orderId는 멱등성 키
public record StockCommand(
        UUID hubId,
        UUID productId,
        int quantity,
        UUID orderId
) {
}
