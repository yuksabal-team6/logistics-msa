# 프로젝트 세팅 가이드

## 목차

1. [빠른 시작](#1-빠른-시작)
2. [구조](#2-구조)
3. [실행](#3-실행)
4. [패키지 구조](#4-패키지-구조)
5. [common 모듈](#5-common-모듈)
6. [엔티티 규칙](#6-엔티티-규칙)
7. [Audit 필드와 소프트 삭제](#7-audit-필드와-소프트-삭제)
8. [예외 처리](#8-예외-처리)
9. [Swagger](#9-swagger)
10. [DB](#10-db)
11. [TODO](#11-todo)
12. [트러블슈팅](#12-트러블슈팅)

---

## 1. 📌 빠른 시작

```bash
git clone https://github.com/yuksabal-team6/logistics-msa.git
cd logistics-msa
docker-compose up -d postgres
```

1. IntelliJ로 **루트 폴더** 열기 → Gradle 탭 🔄
2. `EurekaServerApplication` 먼저 실행 → 나머지 앱 실행 (hotkey: cmd+8)
3. http://localhost:8761 에 6개 UP이면 끝

필요: JDK 17+, Docker + Compose v2 이상, IntelliJ (Gradle 설치 불필요)

- Docker 버전은 팀원끼리 맞출 필요 없음 (이미지 버전은 `postgres:16`, `eclipse-temurin:17-jre`로 고정)
- 확인: `docker compose version` → `v2.x` 이상이면 OK

---

## 2. 구조

Gradle 멀티프로젝트. 루트는 설정만 있고 실행 X

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
| PostgreSQL | 5432 (`logistics`, postgres / postgres) |

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

### IntelliJ

1. `docker-compose up -d postgres`
2. `⌘8` Services 창 → Eureka 먼저 → 나머지 ▶
3. http://localhost:8080/api/v1/orders → 404 JSON이면 정상

내 서비스만 개발: Eureka + 내 서비스 + (Feign으로 부르는 서비스)

### Docker

```bash
./gradlew build -x test && docker-compose up -d --build   # 실행
docker-compose logs -f order-service                       # 로그
docker-compose down                                        # 종료
docker-compose down -v                                     # 종료 + DB 초기화
```

> ⚠️ 이미지는 **내 PC에서 빌드한 jar** 사용. `./gradlew build` 없이 올리면 이전 코드로 실행되니 두 명령을 항상 같이 사용해야 한다.

- 📌 서비스 포트는 외부에 안 열림 → 게이트웨이(8080)로만 요청
- 수평 확장: `docker-compose up -d --scale order-service=2`

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
    ├── presentation/     Controller, dto/request, dto/response
    ├── application/      Service
    ├── domain/           Entity, enum, Repository
    └── infrastructure/   FeignClient, 외부 연동
```

| 서비스 | 도메인 |
| --- | --- |
| user | `user/` |
| company | `company/`, `product/` |
| hub | `hub/`, `route/`, `inventory/` |
| order | `order/` |
| delivery | `delivery/`, `routelog/` |

- 빈 폴더는 git에 안 올라가서 지금은 `domain/`만 있음
- 엔티티 그대로 응답 ❌ → DTO로 변환
- domain에서 Feign·외부 호출 ❌ → application에서

---

## 5. common 모듈

전 서비스 공통 계약만 둔 라이브러리 (실행 X)

| 파일 | 내용 |
| --- | --- |
| `entity/BaseEntity` | Audit 필드 6개 + `markDeleted()` |
| `exception/ErrorCode` | 에러 코드 인터페이스 |
| `exception/CommonErrorCode` | 공통 에러 (400·401·403·404·405·500) |
| `exception/BusinessException` | 비즈니스 예외 |
| `exception/ErrorResponse` | 에러 응답 `{code, message}` |

> ⚠️ 엔티티·비즈니스 로직·서비스 DTO는 넣지 않. common이 바뀌면 전 서비스가 영향을 받습니다. 추가할 땐 팀에 먼저 공유 필수

### common vs 서비스 `global/`

| common (공유) | 각 서비스 `global/` (독립) |
| --- | --- |
| `ErrorResponse`, `BusinessException` | `GlobalExceptionHandler` |
| `ErrorCode`, `CommonErrorCode` | 도메인 에러 enum |
| `BaseEntity` | `SwaggerConfig`, `@ApiErrorCodes`, `JpaAuditingConfig` |

> 💡 전 서비스가 **똑같아야 하는 계약**(응답 형식, 공통 에러, Audit 필드)만 common에 두고, **서비스마다 다르거나 독립적으로 바뀌어야 하는 설정**은 각 서비스 `global/`에 둡니다. `GlobalExceptionHandler`가 서비스마다 비슷하게 중복되지만, 한 서비스 변경이 다른 서비스에 영향을 주지 않도록 중복을 감수한 구조입니다 (MSA의 "결합보다 중복" 원칙).

### 사용

서비스 5개에 이미 연결돼 있음 → import 해서 사용. 새 서비스면 아래 한 줄 추가

```groovy
implementation project(':common')
```

### `compileOnly`

```groovy
// common/build.gradle
compileOnly 'org.springframework.boot:spring-boot-starter-data-jpa'   // BaseEntity
compileOnly 'org.springframework:spring-web'                          // HttpStatus
compileOnly 'io.swagger.core.v3:swagger-annotations-jakarta:2.2.22'   // @Schema
```

> 💡 `compileOnly`는 common을 **컴파일할 때만** 쓰고 다른 모듈로 전달하지 않습니다. 실행 시에는 서비스가 직접 가진 의존성을 씁니다. 그래서 DB 없는 gateway·eureka에 JPA가 딸려가지 않습니다.

| 상황 | 할 일 |
| --- | --- |
| common에 라이브러리 추가 | common엔 `compileOnly` + 쓰는 서비스엔 `implementation` |
| 서비스에서 `ClassNotFoundException` | 서비스 `build.gradle`에 해당 의존성 추가 |

---

## 6. 엔티티 규칙

10개 기준

| 서비스 | 엔티티 |
| --- | --- |
| user | `User` |
| company | `Company`, `Product` |
| hub | `Hub`, `HubRoute`, `Inventory`, `InventoryLog` |
| order | `Order` |
| delivery | `Delivery`, `DeliveryRouteLog` |

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

> 💡 `X-User-Id`는 게이트웨이가 JWT 검증 후 넣어줄 헤더입니다 (아직 TODO) 헤더가 없으면 `created_by`는 null입니다. 서비스에 직접 요청해 테스트할 땐 헤더를 직접 넣어야 한다.

### 📌📌소프트 삭제

```java
// BaseEntity: 삭제 표시만. protected라 서비스에서 직접 호출 불가
protected void markDeleted(UUID deletedBy)

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

```json
{ "code": "ORDER_NOT_FOUND", "message": "주문을 찾을 수 없습니다." }
```

| 종류 | 위치 |
| --- | --- |
| 공통 | `common` → `CommonErrorCode` |
| 도메인 | `global/exception/{서비스}ErrorCode` |

```java
// {서비스}ErrorCode에 추가
ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),

// 던지기
throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
```

`GlobalExceptionHandler` 자동 처리: BusinessException, `@Valid` 실패(400), JSON·타입 오류(400), 없는 URL(404), 메서드 오류(405), 그 외(500)

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
    @ApiErrorCodes(common = {INVALID_INPUT, UNAUTHORIZED}, value = {ORDER_NOT_FOUND})
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) { ... }
}
```

- `@ApiErrorCodes`: `common` = 공통 에러, `value` = 도메인 에러 → 응답 예시 자동 생성
- DTO 필드에 `@Schema(description, example)` 필수

---

## 10. DB

- DB `logistics` 하나, 서비스별 스키마: `user_service`, `company_service`, `hub_service`, `order_service`, `delivery_service`
- 다른 서비스 스키마 직접 조회 ❌ → Feign

### 테이블 생성



- 지금 : `ddl-auto: update`
- 엔티티 확정 후 - `schema.sql` + `validate` (선택)

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

---

## 11. TODO

- 📌 게이트웨이 JWT 필터 → `X-User-Id`, `X-User-Role` 헤더 전달 (헤더 이름 팀 확정 필요). 
- 📌 Swagger 경로(`/swagger-ui/**`, `/v3/api-docs/**`, `/*-service/v3/api-docs`)는 인증 제외
- 회원가입·로그인 (JWT, BCrypt)
- FeignClient: 처음 쓰는 서비스는 Application에 `@EnableFeignClients` 추가
- `schema.sql` + `validate` 전환 (선택)

---

## 12. 트러블슈팅

| 증상 | 해결 |
| --- | --- |
| Gradle 탭·▶ 없음 | 루트 `build.gradle` 우클릭 → Link Gradle Project. 안 되면 `.idea` 삭제 후 다시 열기 |
| `Connection refused` (5432) | `docker-compose up -d postgres` |
| `password authentication failed` | 다른 Postgres가 5432 사용 중 → 중지 |
| `Port already in use` | 떠 있는 앱 종료 |
| 게이트웨이 500/503 `Unable to find instance` | 막 뜬 직후. 30초 후 재시도 |
| Eureka `SELF PRESERVATION ... OFF` 경고 | 의도된 설정, 무시 |
| Docker에 변경 반영 X | `./gradlew build -x test` 먼저 |
| Docker 앱이 죽음 | 메모리 부족 → `colima start --memory 6 --cpus 4` |
