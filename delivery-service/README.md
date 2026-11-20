# delivery-service

## `[수동 처리 필요]` 로그 대응

### 언제 남나
배송을 COMPLETED로 바꾸면 delivery-service는 배송을 커밋한 뒤 order-service에 주문 완료를 알린다(내부 API #10, `POST /internal/orders/{orderId}/complete`).
이 알림이 실패하면(order 4xx·5xx, 타임아웃 등) 배송 API는 200을 그대로 주고 다음 로그만 남긴다.

```
[수동 처리 필요] 주문 완료 처리 실패 orderId=..., deliveryId=..., ...
```

이때 배송은 COMPLETED이고 주문은 CONFIRMED로 어긋나 있다. 주문이 진행 중으로 집계되므로 회원 탈퇴와 업체 삭제가 막힌다.

### 대응: 같은 배송에 완료 요청을 다시 보낸다
order-service가 정상인지 확인한 뒤, 로그의 `deliveryId`로 배송 상태 변경 API를 다시 호출한다.
실패가 4xx(WARN 로그)였다면 order가 요청을 거절한 것이다. 주문이 없거나(RESOURCE_NOT_FOUND), CONFIRMED가 아닌 주문(INVALID_STATUS_TRANSITION, 예: 취소된 주문)이면 다시 보내도 같은 결과가 나온다. 먼저 그 주문의 상태를 확인한다.

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
  이 알림도 실패하면 `[수동 처리 필요]` 로그가 다시 남는다. 원인을 해결한 뒤 같은 요청을 다시 보낸다.
- 권한은 처음 완료할 때와 같다. MASTER는 모든 배송을, DELIVERY는 본인이 배송 담당자인 배송만 요청할 수 있다. 그 외에는 403이다.

### 한계
- 재전송은 사람이 로그를 보고 한다. 실패를 저장해 두었다가 자동으로 다시 보내지는 않는다.
