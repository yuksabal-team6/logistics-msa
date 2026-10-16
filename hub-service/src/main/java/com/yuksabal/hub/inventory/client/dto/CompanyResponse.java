package com.yuksabal.hub.inventory.client.dto;

import java.util.UUID;

// company-service 내부 API #2 업체 조회 응답 중 hub에서 쓰는 필드만 받는다 (나머지 필드는 무시된다)
public record CompanyResponse(
        UUID companyId,
        UUID ownerId
) {
}
