-- 부분 유니크 인덱스: 삭제되지 않은 행끼리만 중복을 막는다 (결정 #15, #18)
-- ddl-auto는 조건부 인덱스를 만들 수 없어 직접 생성한다. IF NOT EXISTS라 재시작해도 안전하다.

CREATE UNIQUE INDEX IF NOT EXISTS uk_deliveries_order_id
    ON delivery_service.p_deliveries (order_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_route_logs_delivery_id
    ON delivery_service.p_delivery_route_logs (delivery_id)
    WHERE deleted_at IS NULL;

-- @Version 추가 전 행은 version이 null → 0으로 채운다 (null이면 낙관적 락이 동작하지 않음)
-- defer-datasource-initialization이라 Hibernate가 컬럼을 추가한 뒤 실행된다. 재시작해도 결과가 같다.
UPDATE delivery_service.p_deliveries SET version = 0 WHERE version IS NULL;
UPDATE delivery_service.p_delivery_route_logs SET version = 0 WHERE version IS NULL;