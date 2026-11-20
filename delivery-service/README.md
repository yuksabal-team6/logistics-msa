# delivery-service

## `[수동 처리 필요]` 로그 대응

### 언제 남나
배송을 COMPLETED로 바꾸면 delivery-service는 배송을 커밋한 뒤 order-service에 주문 완료를 알린다(내부 API #10, `POST /internal/orders/{orderId}/complete`).
이 알림이 실패하면(order 4xx·5xx, 타임아웃 등) 배송 API는 200을 그대로 주고 다음 로그만 남긴다.

```
[수동 처리 필요] 주문 완료 처리 실패 orderId=..., deliveryId=..., ...
```

이 로그는 delivery가 order의 성공 응답을 받지 못했다는 뜻이다. 주문의 실제 상태는 경우마다 다르다.

- 주문이 CONFIRMED로 남아 있음: 배송 COMPLETED · 주문 CONFIRMED로 어긋난 상태다. 주문이 진행 중으로 집계돼 회원 탈퇴와 업체 삭제가 막힌다.
- 주문이 이미 COMPLETED임: order가 완료를 커밋한 뒤 응답만 타임아웃된 경우다. 어긋나지 않았다.
- 주문이 없거나 CONFIRMED·COMPLETED가 아님(예: 취소된 주문): order가 요청을 거절한 경우(4xx, WARN 로그)다.

그래서 로그만 보고 판단하지 말고, 먼저 그 주문의 실제 상태를 확인한다.

### 대응: 주문이 CONFIRMED면 같은 배송에 완료 요청을 다시 보낸다
먼저 로그의 `orderId`로 주문의 실제 상태를 확인한다.

- CONFIRMED면 order-service가 정상인지 확인한 뒤, 로그의 `deliveryId`로 아래 요청을 다시 보낸다.
- 이미 COMPLETED면 다시 보낼 필요가 없다(보내도 변경 없이 200이다).
- 없거나 다른 상태(예: 취소)면 다시 보내도 같은 결과다(RESOURCE_NOT_FOUND, INVALID_STATUS_TRANSITION). 재전송 대상이 아니므로 원인을 따로 확인한다.

```http
PATCH /api/v1/deliveries/{deliveryId}/status
Content-Type: application/json
Authorization: Bearer {MASTER 또는 해당 배송의 배송 담당자(DELIVERY) 토큰}

{ "deliveryStatus": "COMPLETED" }
```

- 이미 COMPLETED인 배송이면 상태와 `completedAt`을 바꾸지 않고 200을 주며, 주문 완료 알림만 다시 보낸다.
- 주문 쪽도 이미 COMPLETED인 주문에는 변경 없이 200을 준다. 그래서 여러 번 다시 보내도 안전하다.
- 재전송하면 delivery-service에 다음 로그가 남는다.
  ```
  [완료 알림 재전송] orderId=..., deliveryId=...
  ```
  이 알림도 실패하면 `[수동 처리 필요]` 로그가 다시 남는다. 원인을 해결한 뒤 주문 상태를 다시 확인하고 같은 요청을 보낸다.
- 권한은 처음 완료할 때와 같다. MASTER는 모든 배송을, DELIVERY는 본인이 배송 담당자인 배송만 요청할 수 있다. 그 외에는 403이다.

### 한계
- 재전송은 사람이 로그를 보고 한다. 실패를 저장해 두었다가 자동으로 다시 보내지는 않는다.
## Feign 재시도 설정

### 원칙
아래쪽 호출의 총 시간이 위쪽 호출의 시간 제한보다 짧아야 한다.
배송 생성(order → delivery) 안에서 delivery가 hub를 부르므로, delivery의 hub 호출이 order의 대기 시간보다 길어지면 order는 생성을 포기하고 배송 취소를 보낸다.
그 뒤에 원래 생성이 늦게 커밋될 수 있다(#302). 늦은 생성은 취소 표식으로 막지만(아래 "늦은 생성과 취소 표식"), 이 구간 자체를 줄이려고 응답 지연 재시도를 끈다.

### 설정
`application.yml`에 hub-service·company-service와 같은 키를 둔다.

```yaml
common:
  feign:
    retry:
      retry-on-read-timeout: false
```

- 응답 지연(read timeout)만 재시도하지 않는다. 5xx·연결 실패는 공통 기본값대로 최대 2회 재시도한다.
- 서비스 단위 설정이다(common `CommonFeignAutoConfiguration`이 서비스에 `Retryer` 빈 하나를 만든다). 그래서 **HubClient·OrderClient·UserClient 모두** 응답 지연 재시도가 꺼진다.
  - OrderClient(배송 완료 → 주문 완료 알림): 응답 지연 한 번이면 실패로 끝나고 `[수동 처리 필요]` 로그가 남는다. 위 "[수동 처리 필요] 로그 대응" 절대로 복구한다.
  - UserClient(담당자 검증): 실패하면 담당자 지정이 502로 끝난다. 다시 요청하면 된다.
- 클라이언트별로 다르게 주려면 `@FeignClient(configuration = ...)`에 `FeignRetryer` 빈을 둬야 한다. 공통 빈보다 우선하는지 확인하지 않아 쓰지 않는다.

### 시간 (설정값으로 계산, 측정 아님)
설정값: connect-timeout 3초, read-timeout 5초(order·delivery 모두 `spring.cloud.openfeign.client.config.default`), 재시도 2회, 재시도 전 대기 0.3초·0.45초(interval 200ms × 1.5ⁿ, feign 13.5 `Retryer.Default`).

| 호출 | 응답 지연일 때 최대 | 연결 타임아웃일 때 최대 |
| --- | --- | --- |
| delivery → hub, 응답 지연 재시도 켬(이전) | 5초 × 3 + 0.75초 = 약 15.75초 | 3초 × 3 + 0.75초 = 약 9.75초 |
| delivery → hub, 응답 지연 재시도 끔(지금) | 5초 × 1 = 약 5초 | 3초 × 3 + 0.75초 = 약 9.75초 (그대로) |
| order → delivery(배송 생성) 한 번의 대기 | 5초 | 3초 |

- 재시도를 꺼도 delivery의 hub 호출(최대 약 5초)과 order의 대기(5초)가 같아서 원칙을 완전히 만족하지는 않는다. 연결 타임아웃 재시도(최대 약 9.75초)도 남아 있다. 늦은 생성이 배송을 남기지 않게 막는 것은 취소 표식이다.
- hub 호출 시간 제한을 order보다 짧게 잡는 방법은 줄일 값을 정할 측정 근거가 없어 쓰지 않았다.

## 늦은 생성과 취소 표식
배송이 없는 주문에 취소(내부 API #9)가 오면 같은 `order_id`로 CANCELLED 행(취소 표식)을 저장한다(#302).

- 표식 = `delivery_status = CANCELLED` + 출발·도착 허브, 수령 업체, 주소, 수령인이 null인 행이다. 구별용 컬럼은 없다.
- 늦게 온 배송 생성은 표식을 기존 배송으로 받는다. 표식이 먼저 저장됐으면 허브를 부르지 않고 반환하고, 생성이 저장 직전이면 부분 유니크 인덱스(`uk_deliveries_order_id`) 위반 뒤 표식을 반환한다.
- 표식은 주문자 ID가 없어 MASTER에게만 보인다. MASTER가 표식을 삭제하면 같은 `order_id` 자리가 비어 보호가 풀린다(삭제는 막지 않는다).
