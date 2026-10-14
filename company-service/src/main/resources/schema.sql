-- 부분 유니크 인덱스 (결정 #15): 삭제되지 않은 행끼리만 중복을 막는다.
-- 엔티티의 unique = true나 @Index로는 WHERE 조건을 줄 수 없어 SQL로 만든다.
-- sql.init.mode: always라 시작할 때마다 실행되므로 IF NOT EXISTS 필수
-- SQL 초기화는 Hibernate의 default_schema를 모르므로 스키마 이름을 직접 쓴다.

-- 사업자 등록번호: NULL(미입력)끼리는 중복으로 보지 않는다
CREATE UNIQUE INDEX IF NOT EXISTS uk_companies_business_number
    ON company_service.p_companies (business_number)
    WHERE deleted_at IS NULL;
