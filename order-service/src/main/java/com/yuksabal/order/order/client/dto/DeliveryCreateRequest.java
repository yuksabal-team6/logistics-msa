package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #5 배송 생성 요청. 출발 = 공급 업체 허브, 도착 = 요청 업체 허브 (같을 수 있음, 결정 #16)
public record DeliveryCreateRequest(
        UUID orderId,
        UUID departureHubId,
        UUID destinationHubId,
        UUID destinationCompanyId,
        String address,
        String recipientName,
        // 선택값 (delivery #12 PR 기준). 주문 생성 요청에 없어 현재는 null 전달
        // TODO [선택] 수령인 slackId를 받게 되면 전달 (요청 업체 관리자 slackId 등, 기준 합의 필요)
        String recipientSlackId,
        // 배송 조회 권한(주문자 본인 배송만)용 (#151). delivery 반영 전에는 모르는 필드라 무시됨
        UUID ordererId,
        // 지금 delivery에서 안 씀. 나중에 업체 기준 조회로 넓힐 때 사용 (#151)
        UUID supplierCompanyId
) {
}
