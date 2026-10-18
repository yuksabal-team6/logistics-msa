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
