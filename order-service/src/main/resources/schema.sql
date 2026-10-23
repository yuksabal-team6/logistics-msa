-- 부분 유니크 인덱스 (결정 15): 삭제되지 않은 행끼리만 중복을 막는다.
-- 엔티티의 unique = true나 @Index로는 WHERE 조건을 줄 수 없어 SQL로 만든다.
-- sql.init.mode: always라 시작할 때마다 실행되므로 IF NOT EXISTS 필수
-- SQL 초기화는 Hibernate의 default_schema를 모르므로 스키마 이름을 직접 쓴다.

-- 멱등성 키: 같은 키의 동시 주문 요청은 하나만 저장된다
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_idempotency_key
    ON order_service.p_orders (idempotency_key)
    WHERE deleted_at IS NULL;

-- 주문번호 (테이블 명세 UQ)
CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_order_number
    ON order_service.p_orders (order_number)
    WHERE deleted_at IS NULL;

-- 주문 목록 조회: 주문자별 최신순 (MASTER 외 사용자는 본인 주문만 조회) (#277)
-- 삭제 안 된 행만 조회하므로(@SQLRestriction) 같은 조건의 부분 인덱스
-- 1년치(120만 행) 측정: 인덱스 없이 목록 162ms, count 42ms(Seq Scan) → 0.073ms, 1.5ms
CREATE INDEX IF NOT EXISTS idx_orders_orderer_created
    ON order_service.p_orders (orderer_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- 주문 목록 조회: MASTER 전체 최신순 (#277)
-- 1년치(120만 행) 측정: 인덱스 없이 목록 180ms(Seq Scan + 정렬), 전체 count 197ms → 0.097ms, 83ms
-- 전체 count는 인덱스로도 행 수에 비례 (약 700만 행부터 500ms 예상) → 그때 count 없는 페이지 응답 검토
CREATE INDEX IF NOT EXISTS idx_orders_created
    ON order_service.p_orders (created_at DESC)
    WHERE deleted_at IS NULL;
