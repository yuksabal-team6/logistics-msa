package com.yuksabal.hub.inventory.domain;

import com.yuksabal.hub.global.exception.HubErrorCode;
import com.yuksabal.hub.global.exception.HubExceptions;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

// 재고 수량 값 객체. 수량 규칙(0 이상, 부족하면 409, 변경량 1 이상)을 한곳에 둔다 (#282)
// 값이 바뀌면 새 객체를 돌려준다(불변). DB에는 기존과 같은 quantity 컬럼 하나로 저장한다
// 수량 규칙은 여기 있지만, 동시 요청끼리 수량을 덮어쓰지 않게 하는 건 재고 행 잠금(차감·복원)과 조건부 UPDATE(입고·조정)다
@Embeddable
public record Quantity(@Column(name = "quantity", nullable = false) int value) {

    // 엔티티 불변식. DTO 검증·DB 제약 뒤의 마지막 방어선이라 정상 요청으로는 걸리지 않는다 (걸리면 코드 버그 → 500)
    public Quantity {
        if (value < 0) {
            throw new IllegalArgumentException("재고 수량은 0 이상이어야 합니다.");
        }
    }

    // 주문 복원으로 늘어난 수량
    public Quantity plus(int amount) {
        checkPositive(amount);
        return new Quantity(Math.addExact(value, amount));
    }

    // 주문 차감으로 줄어든 수량. 남은 수량이 부족하면 409 (details에 현재 수량)
    public Quantity minus(int amount) {
        checkPositive(amount);
        if (value < amount) {
            throw HubExceptions.withCurrentQuantity(HubErrorCode.INSUFFICIENT_STOCK, value);
        }
        return new Quantity(value - amount);
    }

    // 남은 재고가 있는지 (수량 0인 재고는 "재고 없음"으로 본다)
    public boolean isPositive() {
        return value > 0;
    }

    // 요청 수량은 DTO 검증(@Positive)에서 막혀 정상 요청으로는 걸리지 않는다 (걸리면 코드 버그 → 500)
    private static void checkPositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("변경 수량은 1 이상이어야 합니다.");
        }
    }
}
