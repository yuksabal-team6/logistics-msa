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
        String recipientSlackId
) {
}
