-- hub-service DDL: Hibernate ddl-auto로 만들 수 없는 제약·인덱스
-- defer-datasource-initialization: true라 Hibernate가 테이블을 만든 뒤, data.sql보다 먼저 실행된다
-- sql.init.mode: always라 시작할 때마다 실행되므로 다시 실행해도 안전하게 쓴다 (IF NOT EXISTS, DROP IF EXISTS)
-- SQL 초기화는 Hibernate의 default_schema를 모르므로 스키마 이름을 직접 쓴다

-- 재고 로그 change_type CHECK 제약 (Hibernate가 enum으로 만든 제약)
-- ddl-auto update는 기존 CHECK 제약을 바꾸지 않아, InventoryChangeType에 값을 추가하면 여기서 다시 만든다
-- TODO 운영 DB 통합 시 제거하고 일회성 SQL로 한 번만 실행 (sql.init.mode=always라 시작할 때마다 테이블 잠금·전체 행 검사가 일어난다)
ALTER TABLE hub_service.p_inventory_logs DROP CONSTRAINT IF EXISTS p_inventory_logs_change_type_check;
ALTER TABLE hub_service.p_inventory_logs ADD CONSTRAINT p_inventory_logs_change_type_check
    CHECK (change_type IN ('DECREASE', 'RESTORE', 'INBOUND', 'ADJUST_INCREASE', 'ADJUST_DECREASE'));

-- 주문 재고 차감·복원 멱등성 키: 같은 주문의 같은 변경(DECREASE·RESTORE)은 한 번만 기록된다 (결정 #18)
-- 동시에 같은 요청이 들어오면 두 번째 로그 INSERT가 유니크 위반으로 실패해 그 트랜잭션의 재고 변경도 롤백된다
-- 입고·관리자 조정은 order_id가 null이라 대상이 아니다. ddl-auto는 조건부 인덱스를 만들 수 없어 직접 생성한다
CREATE UNIQUE INDEX IF NOT EXISTS uk_inventory_logs_order_id_change_type
    ON hub_service.p_inventory_logs (order_id, change_type)
    WHERE order_id IS NOT NULL;

-- 한 허브에 같은 상품의 활성 재고는 하나다. 주문 차감 UPDATE·조회가 허브 + 상품으로 한 행을 찾는 전제를 DB에서 보장한다
-- 업체 허브가 바뀌어 다른 허브에 같은 상품 재고가 생기는 경우는 허브가 달라 대상이 아니다
-- 활성 중복 행이 이미 있으면 인덱스 생성이 실패해 앱이 시작되지 않으므로, 중복을 먼저 정리해야 한다
CREATE UNIQUE INDEX IF NOT EXISTS uk_inventories_hub_id_product_id
    ON hub_service.p_inventories (hub_id, product_id)
    WHERE deleted_at IS NULL;
