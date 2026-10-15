-- 부분 유니크 인덱스 (결정 #15): 삭제되지 않은 회원끼리만 아이디·이메일 중복을 막는다.
-- 엔티티의 unique = true나 @Index로는 WHERE 조건을 줄 수 없어 SQL로 만든다.
-- sql.init.mode: always라 시작할 때마다 실행되므로 IF NOT EXISTS 필수
-- SQL 초기화는 Hibernate의 default_schema를 모르므로 스키마 이름을 직접 쓴다.

-- 아이디
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username
    ON user_service.p_users (username)
    WHERE deleted_at IS NULL;

-- 이메일: 대소문자를 구분한다 (lower() 미적용)
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email
    ON user_service.p_users (email)
    WHERE deleted_at IS NULL;
