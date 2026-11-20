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

-- 취소 표식(#302)은 허브·수령 업체·주소·수령인 값이 없다. 예전 엔티티(@Column(nullable = false))로 만든 테이블의 NOT NULL을 푼다
-- ddl-auto: update는 기존 컬럼의 NOT NULL을 바꾸지 않는다. defer-datasource-initialization이라 Hibernate가 테이블을 만든 뒤 실행된다
-- 다섯 컬럼 중 NOT NULL인 것이 있을 때만 ALTER를 실행한다. 이미 모두 nullable이면 ALTER(테이블 잠금)를 하지 않는다
-- DO 블록 본문은 $$ 대신 작은따옴표로 감싼다 (안의 작은따옴표는 두 번 겹쳐 쓴다)
--   SQL 초기화 구분자(;)는 그대로다. Spring ScriptUtils.splitSqlScript는 작은따옴표 안의 ;에서 문장을 자르지 않는다
--   본문 안에는 주석과 역슬래시를 쓰지 않는다 (역슬래시는 ScriptUtils가 escape로 본다)
-- 일반 배송의 필수 값은 Delivery 생성자가 막는다 (CHECK 제약은 쓰지 않음)
DO '
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = ''delivery_service''
          AND table_name = ''p_deliveries''
          AND column_name IN (''departure_hub_id'', ''destination_hub_id'', ''destination_company_id'',
                              ''address'', ''recipient_name'')
          AND is_nullable = ''NO''
    ) THEN
        ALTER TABLE delivery_service.p_deliveries
            ALTER COLUMN departure_hub_id DROP NOT NULL,
            ALTER COLUMN destination_hub_id DROP NOT NULL,
            ALTER COLUMN destination_company_id DROP NOT NULL,
            ALTER COLUMN address DROP NOT NULL,
            ALTER COLUMN recipient_name DROP NOT NULL;
    END IF;
END
';
