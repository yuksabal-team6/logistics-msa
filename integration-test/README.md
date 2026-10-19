# 전체 서비스 통합 테스트 (#167)

모든 서비스를 실제로 띄우고 게이트웨이(8080)로 요청해, 서비스 사이 계약과 데이터 흐름을 확인한다. 가짜(Mock)는 없다.
시나리오는 노션 **테스트 시나리오 창구**에서 가져온다 (이름 접두사: ORD 주문 / DLV 배송 / USE 회원 / CMP 업체·상품 / HUB 재고).

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

1. 노션 시나리오를 접두사별 테스트 클래스에 메서드로 옮긴다 (`@DisplayName`에 노션 이름 그대로).
   - ORD → `order/OrderScenarioTest`, DLV → `delivery/DeliveryScenarioTest`, CMP → `company/CompanyScenarioTest`,
     USE → `user/UserScenarioTest`, HUB → `hub/HubScenarioTest`
   - 같은 상황을 다루는 시나리오가 두 접두사에 있으면 테스트 하나에서 양쪽 기대 결과를 함께 확인하고 이름에 둘 다 적는다 (예: `HUB-5 · ORD-21`, `hub/HubScenarioTest`)
   - 커밋은 시나리오 하나당 하나 (메시지에 노션 이름, 예: `[test] ORD-1 회원 주문 생성 시나리오 추가 #167`)
   - 클래스 안의 메서드는 노션 번호순으로 둔다 (작업 순서와 상관없이 번호 자리에 끼워 넣기)
2. 테스트 본문은 노션 본문과 같은 순서로 주석을 단다: `// 사전 조건` → `// 이렇게 하면` → `// 이렇게 돼야 한다`
   - "이렇게 돼야 한다"는 응답 → 다른 서비스 데이터(재고·주문·배송) 순서로 확인하고, 줄마다 무엇을 확인하는지 적는다
   - 왜 그 결과가 나오는지(확인 순서, 어느 서비스가 막는지)가 코드만으로 안 보이면 주석으로 남긴다
   - 준비 요청은 `.expect(200)`, 확인할 요청은 변수로 받아 `assertThat(r.status()).as("%s", r)`
   - 자주 쓰는 요청은 `Scenario` 도우미를 쓴다: `user`, `deliveryDriver`, `order`, `cancel`, `orderDetail`, `ordersOf`, `inventoryOfP`, `stockOfP`, `logsOf`, `changeTypesOf`, `delivery`, `startDelivering`, `completeDelivery`, `deactivateCompany`, `deactivateProduct`, `deactivateUser`
   - 동시 요청은 `Concurrently.run(() -> ..., () -> ...)`으로 한꺼번에 출발시킨다. 어느 쪽이 이길지는 매번 달라서 `Concurrently.count(responses, 409)`처럼 건수와 최종 데이터를 확인한다
   - 오류 응답은 `errorCode()`, `errorMessage()`, `errorFields()`로 읽는다
3. 기대와 다르면 서비스별 버그 이슈를 만들고 `@Disabled("#이슈번호")`로 표시한 뒤 머지한다.
4. 노션 시나리오 상태를 통과 / 이슈 생성으로 바꾼다.

## 알아 둘 것

- 개발 DB를 오래 썼다면 `ddl-auto: update`가 지우지 않은 예전 컬럼이 남아 있을 수 있다.
  예: `order_service.p_orders.requester_company_id`(NOT NULL)가 남아 있으면 주문 생성이 500.
  코드에서 쓰지 않는 예전 컬럼인지 확인한 뒤 지운다.
