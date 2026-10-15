package com.yuksabal.order.order.client.dto;

import java.util.UUID;

// #2 업체 조회 응답. 공급 업체 hubId = 출발 허브, 요청 업체 hubId = 도착 허브
public record CompanyResponse(
        UUID companyId,
        String companyName,
        UUID hubId,
        String address,
        UUID ownerId
) {
}
