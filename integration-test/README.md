# 전체 서비스 통합 테스트 (#167)

모든 서비스를 실제로 띄우고 게이트웨이(8080)로 요청해, 서비스 사이 계약과 데이터 흐름을 확인한다. 가짜(Mock)는 없다.
시나리오는 노션 **테스트 시나리오 창구**에서 가져온다 (이름 접두사: ORD 주문 / DLV 배송 / USR 회원 / CMP 업체·상품 / HUB 재고).

## 실행

```bash
./gradlew build -x test
docker compose -f docker-compose.yml -f docker-compose.it.yml up -d --build
./gradlew :integration-test:integrationTest
```

- `docker-compose.it.yml`: 테스트용 MASTER 계정(`itmaster`)을 만들고, 테스트 주문이 실제 Slack 채널로 가지 않도록 웹훅을 비운다.
- 초기 MASTER는 **MASTER가 하나도 없을 때만** 생성된다. 이미 다른 MASTER가 있으면 그 계정을 넘긴다:
  `./gradlew :integration-test:integrationTest -Dit.masterUsername=... -Dit.masterPassword=...`
- 게이트웨이 주소를 바꾸려면 `-Dit.baseUrl=http://...`
- 평소 `./gradlew build`·`test`에서는 실행되지 않는다 (컴파일만 확인).
- 결과 리포트: `integration-test/build/reports/tests/integrationTest/index.html` (Windows 콘솔은 한글이 깨져 보일 수 있다)

## 데이터

- 개발용 Postgres를 함께 쓴다. 시나리오마다 업체·상품·회원을 새로 만들고 이름에 `[IT]` 접두사를 붙인다.
- 공통 준비(`Scenario`): 공급 업체 A(서울 허브), 요청 업체 B(부산 허브), A의 상품 P, 서울 허브 재고 10개, 회원 u(USER)·a(A 관리자)·b(B 관리자).

## 시나리오 추가

1. 노션 시나리오를 흐름 단위 테스트 클래스에 메서드로 옮긴다 (`@DisplayName`에 노션 이름).
2. 기대와 다르면 서비스별 버그 이슈를 만들고 `@Disabled("#이슈번호")`로 표시한 뒤 머지한다.
3. 노션 시나리오 상태를 통과 / 이슈 생성으로 바꾼다.

## 알아 둘 것

- 개발 DB를 오래 썼다면 `ddl-auto: update`가 지우지 않은 예전 컬럼이 남아 있을 수 있다.
  예: `order_service.p_orders.requester_company_id`(NOT NULL)가 남아 있으면 주문 생성이 500.
  코드에서 쓰지 않는 예전 컬럼인지 확인한 뒤 지운다.
