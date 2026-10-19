package com.yuksabal.company.global.deletion;

import java.util.List;
import java.util.UUID;

// 삭제 전 "남아 있는 업무" 확인 통로 (#137). 확인할 서비스마다 구현체를 하나씩 둔다 (지금은 hub 재고, order 주문)
// DeletionGuard는 이 인터페이스만 알아서, 확인할 서비스가 늘어도 구현체만 추가하면 된다
public interface DeletionChecker {

    // 남은 업무 종류. 409 응답 details의 field로 나간다 (예: "inventory", "order")
    String type();

    // 지우면 안 되는 이유 목록. 없으면 빈 목록
    // 확인하지 못하면(호출 실패·타임아웃·응답 이상) 예외를 던진다 → DeletionGuard가 502로 막는다
    List<String> findBlockers(DeletionTarget target, UUID id);
}
