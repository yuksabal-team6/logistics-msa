# 프로젝트 세팅 가이드

## 목차

1. [빠른 시작](#1--빠른-시작)
2. [구조](#2-구조)
3. [실행](#3-실행)
4. [패키지 구조](#4-패키지-구조)
5. [common 모듈](#5-common-모듈)
6. [엔티티 규칙](#6-엔티티-규칙)
7. [Audit 필드와 소프트 삭제](#7-audit-필드와-소프트-삭제)
8. [예외 처리](#8-예외-처리)
9. [Swagger](#9--swagger)
10. [DB](#10-db)
11. [트러블슈팅](#11-트러블슈팅)

---

## 1. 📌 빠른 시작

```bash
git clone https://github.com/yuksabal-team6/logistics-msa.git
cd logistics-msa
docker-compose up -d postgres
```

1. IntelliJ로 **루트 폴더** 열기 → Gradle 탭 🔄
2. user-service, api-gateway에 JWT 키 넣기 → [10장 JWT 키](#-jwt-키) (없으면 둘 다 안 뜸)
3. `EurekaServerApplication` 먼저 실행 → 나머지 앱 실행 (hotkey: cmd+8 / alt+8 )
   - order-service도 실행한다면 Kafka도 필요: `docker-compose up -d postgres kafka` (주문 Slack 알림이 Kafka를 거침)
4. http://localhost:8761 에 6개 UP이면 끝

필요: JDK 17+, Docker + Compose v2 이상, IntelliJ

---

## 2. 구조

Gradle 멀티프로젝트

```
logistics-msa/
├── build.gradle        공통 버전·의존성 (Boot 3.3.13, Cloud 2023.0.6, Java 17)
├── settings.gradle     모듈 목록
├── docker-compose.yml
├── Dockerfile          서비스 공용
├── common/             공통 라이브러리 (실행 X)
├── eureka-server/
├── api-gateway/
├── user-service/       회원·인증
├── hub-service/        허브·경로·재고
├── company-service/    업체·상품
├── order-service/      주문·Slack
└── delivery-service/   배송·경로 기록
```

### 포트

| 앱 | 포트 |
| --- | --- |
| eureka / gateway | 8761 / 8080 |
| user / hub / company / order / delivery | 8081 / 8082 / 8083 / 8084 / 8085 |
| PostgreSQL | 5432 |
| Kafka | 29092 (로컬 IntelliJ에서 접속), 컨테이너끼리는 `kafka:9092` |

### 게이트웨이 라우팅

| 경로 | 서비스 |
| --- | --- |
| `/api/v1/auth/**`, `/api/v1/users/**` | user |
| `/api/v1/hubs/**`, `/api/v1/hub-routes/**` | hub |
| `/api/v1/companies/**`, `/api/v1/products/**` | company |
| `/api/v1/orders/**` | order |
| `/api/v1/deliveries/**` | delivery |

- 새 외부 경로 → `api-gateway/.../application.yml`에 추가
- `/internal/**`은 라우팅 X (Feign 전용, 외부 접근 불가)

---

## 3. 실행

> 평소 개발은 **IntelliJ** (postgres만 Docker). 요구사항상 최종 동작은 **전체 Docker 실행**으로 확인. 두 방식 동시 실행 ❌ (포트 충돌)

### IntelliJ

1. `docker-compose up -d postgres` (order-service를 실행하면 `docker-compose up -d postgres kafka`)
2. `⌘8`/`Alt+8` Services 창 → Eureka 실행 → 나머지 실행
3. http://localhost:8080/api/v1/orders → 404 JSON이면 정상

> 💡 전부 안 켜도 됨: **Eureka + 내 서비스 + 내 서비스가 호출하는 서비스**만 실행 (예: order가 hub 재고를 차감하면 → Eureka, order, hub)

### Docker

```bash
./gradlew build -x test && docker-compose up -d --build   # 실행
docker-compose logs -f order-service                       # 로그
docker-compose down                                        # 종료
docker-compose down -v                                     # 종료 + DB 초기화
```

- 📌 처음 한 번: `cp .env.example .env` → `JWT_SECRET`에 팀 공용 키 넣기 (없으면 user·gateway 기동 실패)
- ⚠️ 코드 수정 후엔 위 `실행` 명령 그대로 (`./gradlew build` 빠지면 예전 코드로 뜸)
- 📌 서비스 포트(8081~8085) 직접 접속 ❌ → `localhost:8080`(게이트웨이)으로 요청
- (참고) 수평 확장: `docker-compose up -d --scale order-service=2`
- ⚠️ **Docker 메모리 4GB 이상 필요**: Kafka까지 전체가 약 2.2GB를 씀. 2GB(Colima 기본)면 Kafka가 메모리 부족으로 꺼지고 order-service가 계속 재시작 → [11장 트러블슈팅](#11-트러블슈팅)
  - Colima: `colima stop && colima start --cpus 4 --memory 6` / Docker Desktop: Settings → Resources → Memory
- order-service는 Kafka가 정상(healthcheck)이 된 뒤에 뜸 (`depends_on: kafka: service_healthy`)

### 📌 Kafka (order 주문 알림)

주문 Slack 알림은 Outbox + Kafka로 비동기 전송 (#198). 주문 API는 Slack을 직접 부르지 않음

```
주문 상태 변경 + 알림 이벤트 저장 (같은 트랜잭션, p_order_outbox)
  → OrderOutboxPublisher (1초마다 미발행 이벤트 발행)
  → Kafka 토픽 order.events
  → OrderEventListener (consumer group order-slack-notifier) → SlackNotifier → Slack
```

| 설정 (`order-service/application.yml`) | 기본값 | 설명 |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Docker에서는 `kafka:9092` (compose에 설정됨) |
| `ORDER_OUTBOX_ENABLED` | `true` | outbox 발행 스케줄러 켜기 |
| `ORDER_OUTBOX_POLL_INTERVAL_MS` | `1000` | 발행 주기 (이전 회차 끝나고 쉬는 시간) |
| `ORDER_OUTBOX_BATCH_SIZE` | `100` | 한 회차 최대 발행 건수 (발행 용량 약 88건/초, 성능 테스트 근거) |

- Kafka가 꺼져 있어도 주문은 정상 처리되고, 알림은 outbox에 쌓였다가 Kafka가 살아나면 발행됨
- 전달 보장은 at-least-once: 드물게 같은 알림이 두 번 갈 수 있음
- 테스트: `application-test.yml`에서 리스너, 토픽 생성, outbox 스케줄러를 끔. Kafka 연동 테스트만 `@EmbeddedKafka`로 테스트용 Kafka를 띄움

---

## 4. 패키지 구조

도메인 → 계층 순으로 나눔

```
order-service/.../com/yuksabal/order/
├── global/
│   ├── config/           JpaAuditingConfig, SwaggerConfig
│   ├── exception/        GlobalExceptionHandler, OrderErrorCode
│   └── swagger/          @ApiErrorCodes
└── order/
    ├── controller/       Controller
    ├── dto/
    │   ├── request/
    │   └── response/
    ├── service/          Service
    ├── repository/       Repository
    ├── entity/           Entity, enum
    └── client/           {대상서비스}Client (예: HubClient)
        └── dto/          Feign 요청·응답 DTO
```

| 서비스 | 도메인 |
| --- | --- |
| user | `user/` |
| company | `company/`, `product/` |
| hub | `hub/`, `route/`, `inventory/` |
| order | `order/` |
| delivery | `delivery/`, `routelog/` |

- 엔티티 그대로 응답 ❌ → DTO로 변환
- Feign·외부 호출은 `service`에서만 (controller·entity에서 ❌)
- Feign DTO는 `client/dto/`에 따로 (내 API용 `dto/`와 섞지 않음, 필요한 필드만)

---

## 5. common 모듈

전 서비스가 똑같이 따라야 하는 약속(응답 형식, 예외 처리, Feign 설정)을 모은 라이브러리 

| 파일 | 내용 |
| --- | --- |
| `entity/BaseEntity` | Audit 필드 6개 + `markDeleted()` |
| `exception/ErrorCode` | 에러 코드 인터페이스 |
| `exception/CommonErrorCode` | 공통 에러 코드 (API 명세 0.5) |
| `exception/BusinessException` | 비즈니스 예외 (message·field 지정 가능) |
| `exception/GlobalExceptionHandler` | 전역 예외 처리 → `ApiResponse` 실패 응답 |
| `response/ApiResponse`, `ApiError`, `ApiErrorDetail` | 공통 성공·실패 응답 |
| `response/PageResponse` | 공통 목록 응답 |
| `feign/` | 헤더 전달, 에러 디코더, Feign 예외 처리 (Feign 있는 서비스만 적용) |
| `config/` | 위 기능을 서비스에 자동 등록 (AutoConfiguration) |

> 💡 서비스는 자기 패키지(`com.yuksabal.{서비스}`)만 스캔하므로, common의 예외 처리·Feign 설정은 `common/src/main/resources/META-INF/spring/...AutoConfiguration.imports`로 자동 등록됩니다. 서비스에서 따로 할 일 없음

### common에 넣는 기준

| common ✅ (도메인을 모르는 것) | 서비스 ❌ (도메인을 아는 것) |
| --- | --- |
| 응답 형식, 공통 에러 코드, 전역 예외 처리 | 엔티티, Repository, 비즈니스 로직 |
| Feign 공통 설정 (헤더 전달, 에러 디코더) | `@FeignClient` 인터페이스, Feign DTO (`client/`) |
| `BaseEntity` | 도메인 에러 enum (`{서비스}ErrorCode`) |
| | `SwaggerConfig`, `@ApiErrorCodes`, `JpaAuditingConfig` (서비스 `global/`) |

> ⚠️ common이 바뀌면 전 서비스가 영향을 받습니다. "여러 서비스가 쓰니까"만으로 넣지 말고, **도메인을 모르는 공통 약속**인지 확인 후 팀에 먼저 공유

### 응답 형식

```json
// 성공: ApiResponse.success(data) 또는 ApiResponse.success(data, "메시지")
{ "success": true, "data": { "id": "8b2d..." }, "message": "요청이 성공했습니다." }

// 목록: ApiResponse.success(PageResponse.from(page), "목록을 조회했습니다.")
{ "success": true,
  "data": { "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 },
  "message": "목록을 조회했습니다." }

// 실패: 예외를 던지면 GlobalExceptionHandler가 자동 변환 (details는 없으면 생략)
{ "success": false,
  "error": { "code": "RESOURCE_NOT_FOUND", "message": "존재하지 않거나 삭제된 상품입니다.",
             "details": [{ "field": "productId", "reason": "존재하지 않거나 삭제된 상품입니다." }] } }
```

- 반환할 데이터가 없으면 `ApiResponse.success(null)` → `data` 필드 빠짐
- Feign으로 다른 서비스 호출 시에도 `ApiResponse<HubResponse>`로 받아서 `.data()` 사용

### 목록 조회

- 컨트롤러 파라미터에 `@ParameterObject Pageable pageable` → `?page=0&size=20&sort=createdAt,desc`
- 기본값은 common에서 자동 적용: `page=0`, `size=20` (최대 100, 넘으면 100으로), 정렬 미지정 시 `createdAt DESC`
- `Page`를 그대로 반환 ❌ → `PageResponse.from(page)` 또는 `PageResponse.from(entityPage, XxxResponse::from)`
- 정렬 가능 필드는 API별로 제한 (명세 0.4). 없는 필드로 정렬 요청 시 400 `VALIDATION_ERROR`

### 사용

서비스 5개에 이미 연결돼 있음 → import 해서 사용. 새 서비스면 아래 한 줄 추가

```groovy
implementation project(':common')
```

### `compileOnly`

```groovy
// common/build.gradle
compileOnly 'org.springframework.boot:spring-boot-starter-web'        // 예외 처리, 자동 설정
compileOnly 'org.springframework.boot:spring-boot-starter-data-jpa'   // BaseEntity, PageResponse
compileOnly 'org.springframework.cloud:spring-cloud-starter-openfeign' // feign/
compileOnly 'io.swagger.core.v3:swagger-annotations-jakarta:2.2.22'   // @Schema
```

> 💡 `compileOnly`: common은 빌드할 때만 라이브러리를 참고하고, 서비스에 같이 넘겨주지 않음
> - 라이브러리는 **서비스가 직접** `build.gradle`에 추가해야 동작
> - 예) user-service는 초기 Feign 의존성 없음 → common Feign 설정도 안 붙음. 
>   - 필요해지면 `openfeign` 의존성 + `@EnableFeignClients`만 추가 (공통 설정 자동 적용)

- common에 라이브러리 추가하는 경우 -> common엔 `compileOnly` + 서비스엔 `implementation`
  - 서비스쪽 추가 누락 시 `ClassNotFoundException` 오류-> 서비스 `build.gradle`에 해당 의존성 추가하여 해결 가능

---

## 6. 엔티티 규칙

10개 기준

- **User Service**: `User`
- **Company Service**: `Company`, `Product`
- **Hub Service**: `Hub`, `HubRoute`, `Inventory`, `InventoryLog`
- **Order Service**: `Order`
- **Delivery Service**: `Delivery`, `DeliveryRouteLog`

> ✏️ 담당자가 필요에 따라 수정할 수 있습니다. 수정하면 **ERD 문서도 같이 갱신**하고, 다른 서비스가 참조하는 컬럼을 바꿀 땐 해당 담당자에게 먼저 공유하세요.

| 규칙 | 비고 |
| --- | --- |
| `BaseEntity` 상속 | Audit 필드 자동 |
| `@SQLRestriction("deleted_at IS NULL")` | 삭제 데이터 자동 제외. 상속 안 돼서 엔티티마다 |
| `@Table(name = "p_orders")` + `class Order` | 테이블 `p_` 복수형 |
| PK: `@GeneratedValue(strategy = UUID)` + `@Column(name = "order_id")` | |
| `unique = true` ❌ | 삭제된 행과 충돌. 중복은 `existsBy...`로 체크 |
| `@OneToOne` ❌ → `@ManyToOne` | `@OneToOne`도 UNIQUE 생성 |
| 다른 서비스 엔티티 참조 ❌ → `UUID`만 | 존재 확인은 Feign |
| 같은 서비스 안은 `@ManyToOne(fetch = LAZY)` | 예: Product → Company |
| `@Enumerated(EnumType.STRING)` | |
| `@NoArgsConstructor(access = PROTECTED)` + `@Builder` | |
| setter ❌ → 의미 있는 메서드 | `order.cancel()` |

---

## 7. Audit 필드와 소프트 삭제

### Audit 필드

| 필드 | 채워지는 방식 |
| --- | --- |
| `created_at`, `updated_at` | 자동 |
| `created_by`, `updated_by` | 요청 헤더 `X-User-Id`로 자동 |
| `deleted_at`, `deleted_by` | `delete()` 호출 시 |

> 💡 `X-User-Id`는 게이트웨이가 JWT 검증 후 넣어주는 헤더입니다. 서비스 포트로 직접 요청해 테스트할 땐 헤더를 직접 넣어야 하고, 없으면 `created_by`는 null입니다.

### 📌📌 소프트 삭제

```java
// BaseEntity: 삭제 표시만. protected라 서비스에서 직접 호출 불가
protected void markDeleted(UUID deletedBy);

// 엔티티: 삭제 가능한 엔티티만 delete() 보유
public void delete(UUID deletedBy) { markDeleted(deletedBy); }
```

```java
// 사용
Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
order.delete(userId);   // @Transactional 끝나면 UPDATE
```

- `repository.delete()` ❌ (실제 DELETE 됨)
- 삭제 후 조회에서 자동 제외
- 삭제 조건이 필요하면 `delete()` 안에 추가

| delete() 있음 | delete() 없음 |
| --- | --- |
| User, Company, Product, Inventory, Order, Delivery, DeliveryRouteLog | Hub, HubRoute (조회 전용), InventoryLog (이력·멱등성 키) |

---

## 8. 예외 처리

공통 에러 코드 (`CommonErrorCode`, API 명세 0.5)

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR`, `INVALID_STATUS` | 필수값 누락, 형식 오류, 잘못된 상태값 |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 403 | `FORBIDDEN` | 권한 부족 |
| 404 | `RESOURCE_NOT_FOUND` | 없거나 Soft Delete된 리소스 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 메서드 |
| 409 | `DUPLICATE_RESOURCE` | 중복 |
| 422 | `BUSINESS_RULE_VIOLATION` | 업무 규칙 위반 |
| 429 | `TOO_MANY_REQUESTS` | 요청 횟수 제한 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |
| 502 | `DEPENDENCY_FAILURE` | 다른 서비스 호출 실패 (Feign 5xx·타임아웃) |
| 503 | `SERVICE_UNAVAILABLE` | 서비스 이용 불가 |

도메인 에러는 `global/exception/{서비스}ErrorCode`에 추가 

| 서비스 | 코드 |
| --- | --- |
| user | `INVALID_CREDENTIALS` (401) |
| hub | `INSUFFICIENT_STOCK`, `INVENTORY_CONFLICT` (409) |
| company | `COMPANY_ACCESS_DENIED` (403) |
| order·delivery | `INVALID_STATUS_TRANSITION` (409), delivery는 `DELIVERY_ACCESS_DENIED` (403), `DELIVERY_CONFLICT` (409, 동시 수정) |
| gateway | `TOKEN_EXPIRED` (401) |

```java
// 404: 리소스별 NOT_FOUND 코드를 만들지 않고 RESOURCE_NOT_FOUND + message + field
throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않거나 삭제된 상품입니다.", "productId");

// 도메인 에러: {서비스}ErrorCode에 추가 후 던지기
INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),
throw new BusinessException(HubErrorCode.INSUFFICIENT_STOCK);
```

`GlobalExceptionHandler` 자동 처리

| 예외 | 응답 |
| --- | --- |
| `BusinessException` | 지정한 코드·메시지·details |
| `@Valid` 실패 | 400 `VALIDATION_ERROR` + 실패한 필드 **전부** details |
| JSON 형식 오류 | 400 `VALIDATION_ERROR` |
| 파라미터 타입 오류, 필수 파라미터·헤더 누락, 없는 정렬 필드 | 400 `VALIDATION_ERROR` + 해당 필드 details |
| 없는 URL / 메서드 오류 | 404 `RESOURCE_NOT_FOUND` / 405 `METHOD_NOT_ALLOWED` |
| DB 유니크 위반 (동시 요청 등) | 409 `DUPLICATE_RESOURCE` (NOT NULL·FK 위반은 500) |
| Feign 실패 | 아래 표 |
| 그 외 | 500 `INTERNAL_SERVER_ERROR` |

> 💡 서비스에서 특정 예외를 다르게 처리하고 싶으면 서비스에 `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`로 추가 (common보다 먼저 적용)

### Feign(내부 API) 호출 규칙

- 경로 `/internal/**`, 게이트웨이에 등록 ❌ / `@FeignClient(name = "hub-service")`처럼 Eureka 서비스 이름 사용
- `X-User-Id`, `X-User-Role`은 common `FeignHeaderInterceptor`가 자동 전달 (직접 넣지 않음)
- 내부 API도 `ApiResponse` 형식으로 응답
- 실패는 common `FeignErrorDecoder`가 예외로 변환

| 상황 | 예외 | 의미 | 안 잡으면 |
| --- | --- | --- | --- |
| 상대가 4xx | `FeignClientException` (status, code, message, details) | 업무 실패 (재고 부족 등) | 상대의 상태·error 그대로 응답 |
| 상대가 5xx | `FeignServerException` | 재시도·보상 대상 | 502 `DEPENDENCY_FAILURE` |
| 타임아웃·연결 실패 | `feign.RetryableException` | 재시도·보상 대상 | 502 `DEPENDENCY_FAILURE` |

> 💡 5xx·타임아웃·연결 실패는 최대 2회 재시도 후 예외 (4xx는 재시도 없이 바로 예외). 재시도 후에도 예외 종류는 위 표와 같음

- 내부 POST API는 같은 요청을 다시 받아도 결과가 같게(멱등) 만든다 (재시도 때문에)
- 내부 클라이언트에서 재시도를 끄려면 `Retryer.NEVER_RETRY` 대신 `common.feign.retry.max-retries: 0` 또는 `FeignRetryer.noRetry()`를 쓴다 (`NEVER_RETRY`면 5xx가 `FeignServerException`이 아니라 `RetryableException`으로 나옴)
- `Retryer.NEVER_RETRY`는 자기 디코더를 쓰는 외부 API(Slack 등) Feign 클라이언트에만 지정한다
- 서비스가 `Retryer` 빈을 직접 만들 때도 `FeignRetryer`를 써야 예외 종류가 유지된다

재시도 설정 (서비스 `application.yml`에서 변경, 없으면 기본값)

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `common.feign.retry.max-retries` | `2` | 재시도 횟수 (처음 호출 제외). `0`이면 재시도 안 함 |
| `common.feign.retry.interval` | `200` | 첫 재시도 전 대기(ms). 이후 1.5배씩 증가 |
| `common.feign.retry.max-interval` | `1000` | 대기 상한(ms) |
| `common.feign.retry.retry-on-read-timeout` | `true` | 응답 지연(read timeout)도 재시도할지 |

```java
try {
    hubClient.decreaseStock(request);
} catch (FeignServerException | RetryableException e) {
    // 보상 처리 (예: 주문 실패 처리, 이미 차감한 재고 restore)
    throw e;
}
```

- `stocks/decrease`, `stocks/restore`는 `orderId` 기준 멱등성 처리 (`InventoryLog`의 order_id + change_type 중복 확인)
- 외부 API(Slack 등)는 응답 형식이 달라서 common 디코더가 맞지 않음 → `@FeignClient(configuration = XxxFeignConfig.class)`로 별도 지정

---

## 9. 📌 Swagger

- **통합 (게이트웨이)**: http://localhost:8080/swagger-ui/index.html → 오른쪽 위 드롭다운에서 서비스 선택
  - fetch error 500 발생 가능: 서비스가 Eureka에 등록되고 → 게이트웨이가 Eureka에서 그 목록을 받아오기까지 최대 30초 소요됨.
  
- 서비스별: `http://localhost:{8081~8085}/swagger-ui/index.html` (IntelliJ 실행 시만. Docker는 서비스 포트가 외부에 안 열림)
- 우상단 Authorize에 토큰만 입력 → `Bearer ` 자동 붙여줌

> 💡 통합 Swagger의 Try it out 요청은 **게이트웨이를 거쳐** 서비스로 갑니다 (실제 인증 흐름 그대로). Docker로 실행해도 볼 수 있습니다. 새 서비스를 추가하면 게이트웨이 `application.yml`에 api-docs 라우팅과 `springdoc.swagger-ui.urls`를 추가하세요.

```java
@Tag(name = "Order", description = "주문 API")
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @Operation(summary = "주문 생성")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED}, value = {INVALID_STATUS_TRANSITION})
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(orderService.create(request)));
    }
}
```

- `@ApiErrorCodes`: `common` = 공통 에러, `value` = 도메인 에러 → 응답 예시 자동 생성
- DTO 필드에 `@Schema(description, example)` 필수

---

## 10. DB

- DB `logistics` 하나, 서비스별 스키마: `user_service`, `company_service`, `hub_service`, `order_service`, `delivery_service`
- 다른 서비스 스키마 직접 조회 ❌ → Feign
- DB 툴(DBeaver 등) 접속: `localhost:5432` / DB `logistics` / ID `postgres` / PW `postgres`
- `order_service.p_order_outbox`: 주문 알림 이벤트 우편함 (Kafka 발행 후에도 행은 남고 `published_at`만 기록)

### 테이블 생성

- 테이블: `ddl-auto: update` (엔티티로 자동 생성)
- 부분 유니크 인덱스(`WHERE deleted_at IS NULL`): 서비스별 `schema.sql` (예: company-service)
  - `application.yml`에 `spring.sql.init.mode: always` + `spring.jpa.defer-datasource-initialization: true`
  - 스키마 이름 직접 쓰기 (`company_service.p_companies`), `IF NOT EXISTS` 필수
  - 조회 성능용 부분 인덱스도 `schema.sql`에 둠 (예: order-service의 outbox 미발행 조회 인덱스)

> ⚠️ `update`는 컬럼 **추가만** 반영합니다 (삭제·이름 변경 X). 테이블이 꼬이면 `docker-compose down -v && docker-compose up -d postgres`로 초기화하세요.

### 📌 초기 데이터 (hub-service `data.sql`)

| 데이터 | 건수 | ID |
| --- | --- | --- |
| 허브 | 17 | `00000000-0000-0000-0000-0000000000NN` (01 서울 ~ 17 경남) |
| 경로 | 272 (전체 쌍 양방향) | `00000000-0000-0000-00DD-0000000000AA` (DD 출발, AA 도착) |

> 💡 `spring.sql.init.mode: always`는 매번 **실행**만 하고 지우지 않습니다. 이미 있는 행은 건너뛰어서 처음에만 들어가고, 재시작해도 기존 데이터는 그대로입니다. 처음 상태로 되돌리려면 DB를 초기화하세요.

> 💡 경로 거리·시간은 INSERT 시 한 번 계산한 예상치입니다: 직선거리(Haversine) × 1.3(표준으로 사용하는 도로 우회 계수), 평균 시속 60km. 예) 서울 → 부산 407.98km, 408분

- 01 서울 · 02 경기 북부 · 03 경기 남부 · 04 부산 · 05 대구 · 06 인천
- 07 광주 · 08 대전 · 09 울산 · 10 세종 · 11 강원 · 12 충북
- 13 충남 · 14 전북 · 15 전남 · 16 경북 · 17 경남

### 📌 설정 파일

| 파일 | 용도 | git |
| --- | --- | --- |
| `application.yml` | 포트, DB, Eureka | ✅ |
| `application-secret.yml` | JWT 시크릿, Slack URL (resources에 직접 생성) | ❌ |
| `.env` | Docker용 비밀값 (`.env.example` 복사) | ❌ |

> ⚠️ 비밀값은 커밋 ❌ 공유는 슬랙 또는 노션 사용.

### 📌 JWT 키

user-service(발급)와 api-gateway(검증)가 **같은 키**를 써야 함 → 다르면 모든 요청 401

```yaml
# user-service, api-gateway 각각 src/main/resources/application-secret.yml
jwt:
  secret: 팀 공용 키 (슬랙·노션)
```

- Docker는 `.env`의 `JWT_SECRET` 하나로 두 서비스에 같이 들어감
- 키가 없거나 형식이 틀리면 user·gateway **기동 실패** 
- 토큰 규격: HS256, `sub` = userId, `role`, `iss` = user-service, 만료 1시간

로그인 흐름

1. `POST /api/v1/auth/login` → `{ accessToken, tokenType: "Bearer", expiresIn }` (`expiresIn` 단위: 초)
2. 이후 요청 헤더: `Authorization: Bearer {accessToken}` (Swagger는 Authorize 버튼에 토큰만 입력)
3. 게이트웨이가 검증 후 `X-User-Id`, `X-User-Role`을 서비스로 전달

---

## 11. 트러블슈팅

### 🔧 자주 발생하는 오류 및 해결 방법

- **Gradle 탭 또는 실행 버튼(▶)이 안 보일 때**
  - 루트 `build.gradle` 우클릭 → `Link Gradle Project`
  - 해결되지 않으면 IntelliJ 종료 후 `.idea` 삭제하고 다시 열기

- **`Connection refused` (5432)**
  - PostgreSQL 컨테이너 실행: `docker-compose up -d postgres`

- **`password authentication failed`**
  - 다른 PostgreSQL이 5432 포트를 사용 중인지 확인 후 중지

- **`Port already in use`**
  - 해당 포트를 사용 중인 애플리케이션 종료

- **`JWT_SECRET 환경변수가 설정되지 않았습니다` (user·gateway 기동 실패)**
  - IntelliJ: 해당 서비스 `application-secret.yml`에 `jwt.secret` 추가 / Docker: `.env`에 `JWT_SECRET` → [10장 JWT 키](#-jwt-키)

- **로그인은 되는데 다른 요청이 전부 401 `UNAUTHORIZED`**
  - user와 gateway의 JWT 키가 다른지 확인 (같은 공용 키여야 함)

- **Gateway 500/503: `Unable to find instance`**
  - 서비스 시작 직후라면 Eureka 등록을 기다린 뒤 약 30초 후 재시도

- **Eureka: `SELF PRESERVATION ... OFF`**
  - 의도된 설정이므로 경고 무시

- **Docker에 코드 변경사항이 반영되지 않을 때**
  - `./gradlew build -x test` 실행 후 이미지 재빌드 및 컨테이너 재생성

- **order-service가 계속 재시작: `No resolvable bootstrap urls` / `Failed to construct kafka consumer`**
  - Kafka 컨테이너가 안 떠 있거나 메모리 부족으로 꺼진 것: `docker inspect logistics-kafka --format '{{.State.OOMKilled}}'`가 `true`면 메모리 부족
  - Docker 메모리를 4GB 이상으로 늘린 뒤 `docker-compose up -d kafka`

- **IntelliJ로 order 실행 시 `Connection to node ... could not be established` 경고가 반복**
  - 로컬 Kafka가 없음: `docker-compose up -d kafka` (order는 `localhost:29092`로 접속)

- **주문은 되는데 Slack 알림이 안 옴**
  - 미발행 이벤트 확인: `select count(*) from order_service.p_order_outbox where published_at is null;` → 쌓여 있으면 Kafka 상태 확인
  - Kafka에 쌓인 메시지(lag) 확인: `docker exec logistics-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group order-slack-notifier`
  - 둘 다 정상이면 Slack Webhook URL(`.env`) 확인

- **Docker 애플리케이션이 종료될 때**
  - 메모리 부족 여부 확인
  - Colima 리소스 조정: `colima start --memory 6 --cpus 4`
